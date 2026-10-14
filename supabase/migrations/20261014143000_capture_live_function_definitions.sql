-- Reconcile two currently deployed function definitions that were not fully
-- represented by the historical migration chain.
-- This is a forward-only source capture: no tables or financial rows are changed.
-- ingest_financial_envelope uses the existing (business_id, operation_id) primary key.
-- list_admin_cashiers is a read-only RPC already used by older clients.

CREATE OR REPLACE FUNCTION public.ingest_financial_envelope(p_business_id text, p_operation_id text, p_branch_id text, p_actor_membership_id text, p_device_id text, p_operation_type text, p_schema_version integer, p_occurred_at timestamp with time zone, p_branch_timezone_id text, p_business_local_date date, p_canonical_text text, p_canonical_sha256 text, p_canonical_payload jsonb)
 RETURNS TABLE(status text, operation_id text, canonical_sha256 text, duplicate boolean)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_uid uuid := (select auth.uid());
  v_membership public.business_memberships%rowtype;
  v_existing_hash text;
  v_inserted_hash text;
begin
  if v_uid is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;

  -- The caller must be an ACTIVE ADMIN, acting as their own membership,
  -- for exactly this business and branch.
  select m.* into v_membership
  from public.business_memberships m
  join public.businesses b on b.id = m.business_id and b.is_active
  join public.branches br on br.id = m.branch_id
       and br.business_id = m.business_id and br.is_active
  join public.profiles p on p.auth_user_id = m.auth_user_id and p.is_active
  where m.id = p_actor_membership_id
    and m.auth_user_id = v_uid
    and m.role = 'ADMIN'
    and m.is_active
    and m.staff_identity_id is null
    and m.business_id = p_business_id
    and m.branch_id = p_branch_id;

  if not found then
    raise exception 'INVALID_ADMIN_MEMBERSHIP' using errcode = '42501';
  end if;

  if p_operation_type is null or p_operation_type not in (
    'RECORD_SALE','RECORD_CREDIT_REPAYMENT','RECORD_COUPON_PURCHASE',
    'RECORD_AMOUNT_CORRECTION','RECORD_PAYMENT_METHOD_CHANGE','RECORD_VOID',
    'RECORD_REFUND','RECORD_EXPENSE','RECORD_EXPENSE_CORRECTION',
    'RECORD_EXPENSE_VOID','OPEN_CASH_SHIFT','COUNT_CASH_SHIFT','CLOSE_CASH_SHIFT'
  ) then
    raise exception 'UNSUPPORTED_OPERATION_TYPE';
  end if;

  if p_schema_version is null or p_schema_version < 1
     or p_device_id is null or length(btrim(p_device_id)) not between 1 and 128
     or p_operation_id is null or length(btrim(p_operation_id)) = 0
     or p_canonical_text is null or octet_length(p_canonical_text) not between 2 and 262144
     or p_canonical_sha256 is null or p_canonical_sha256 !~ '^[0-9a-f]{64}$'
     or p_canonical_payload is null or jsonb_typeof(p_canonical_payload) <> 'object'
     or p_occurred_at is null or p_business_local_date is null
     or p_branch_timezone_id is null
  then
    raise exception 'INVALID_ENVELOPE';
  end if;

  if p_canonical_sha256 <> encode(
       extensions.digest(convert_to(p_canonical_text, 'UTF8'), 'sha256'), 'hex') then
    raise exception 'DIGEST_MISMATCH';
  end if;

  if p_canonical_payload <> p_canonical_text::jsonb
     or p_canonical_payload ->> 'operation_id' is distinct from p_operation_id
     or p_canonical_payload ->> 'business_id' is distinct from p_business_id then
    raise exception 'PAYLOAD_MISMATCH';
  end if;

  if p_occurred_at > now() + interval '10 minutes' then
    raise exception 'OCCURRED_AT_IN_FUTURE';
  end if;

  if not exists (
    select 1 from public.branches br
    where br.id = p_branch_id and br.business_id = p_business_id
      and br.timezone_id = p_branch_timezone_id
  ) then
    raise exception 'BRANCH_TIMEZONE_MISMATCH';
  end if;

  if p_business_local_date is distinct from
     (p_occurred_at at time zone p_branch_timezone_id)::date then
    raise exception 'LOCAL_DATE_MISMATCH';
  end if;

  insert into private.financial_operation_envelopes as e (
    business_id, operation_id, branch_id, actor_membership_id, device_id,
    operation_type, schema_version, occurred_at, branch_timezone_id,
    business_local_date, canonical_text, canonical_sha256, canonical_payload
  ) values (
    p_business_id, p_operation_id, p_branch_id, v_membership.id, p_device_id,
    p_operation_type, p_schema_version, p_occurred_at, p_branch_timezone_id,
    p_business_local_date, p_canonical_text, p_canonical_sha256, p_canonical_payload
  )
  on conflict on constraint financial_operation_envelopes_pkey do nothing
  returning e.canonical_sha256 into v_inserted_hash;

  if v_inserted_hash is not null then
    return query select 'ACKNOWLEDGED'::text, p_operation_id, p_canonical_sha256, false;
    return;
  end if;

  select e.canonical_sha256 into v_existing_hash
  from private.financial_operation_envelopes e
  where e.business_id = p_business_id and e.operation_id = p_operation_id;

  if v_existing_hash is distinct from p_canonical_sha256 then
    raise exception 'OPERATION_COLLISION';
  end if;

  return query select 'ACKNOWLEDGED'::text, p_operation_id, p_canonical_sha256, true;
end;
$function$;

REVOKE ALL ON FUNCTION public.ingest_financial_envelope(text, text, text, text, text, text, integer, timestamptz, text, date, text, text, jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.ingest_financial_envelope(text, text, text, text, text, text, integer, timestamptz, text, date, text, text, jsonb) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.list_admin_cashiers()
 RETURNS TABLE(local_user_id text, membership_id text, branch_id text, display_name text, canonical_phone text, is_active boolean)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
    v_auth_user_id uuid := (SELECT auth.uid());
BEGIN
    IF v_auth_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING errcode = '42501';
    END IF;

    RETURN QUERY
    SELECT
        cashier.local_user_id,
        cashier.id,
        cashier.branch_id,
        staff.display_name,
        staff.canonical_phone,
        (cashier.is_active AND staff.is_active)
    FROM public.business_memberships AS admin_membership
    JOIN public.profiles AS admin_profile
      ON admin_profile.auth_user_id = admin_membership.auth_user_id
     AND admin_profile.is_active = true
    JOIN public.businesses AS business
      ON business.id = admin_membership.business_id
     AND business.is_active = true
    JOIN public.branches AS branch
      ON branch.id = admin_membership.branch_id
     AND branch.business_id = admin_membership.business_id
     AND branch.is_active = true
    JOIN public.business_memberships AS cashier
      ON cashier.business_id = admin_membership.business_id
     AND cashier.branch_id = admin_membership.branch_id
     AND cashier.role = 'CASHIER'
    JOIN private.staff_identities AS staff
      ON staff.id = cashier.staff_identity_id
    WHERE admin_membership.auth_user_id = v_auth_user_id
      AND admin_membership.role = 'ADMIN'
      AND admin_membership.is_active = true
      AND admin_membership.staff_identity_id IS NULL
      AND cashier.auth_user_id IS NULL
      AND cashier.staff_identity_id IS NOT NULL
      AND cashier.local_user_id IS NOT NULL
      AND btrim(cashier.local_user_id) <> ''
    ORDER BY staff.display_name, cashier.local_user_id;
END;
$function$;

REVOKE ALL ON FUNCTION public.list_admin_cashiers() FROM PUBLIC, anon, service_role;
GRANT EXECUTE ON FUNCTION public.list_admin_cashiers() TO authenticated;
COMMENT ON FUNCTION public.list_admin_cashiers() IS 'Read-only cashier directory restricted to authenticated active branch administrators.';

-- Repository change only. NOT applied to production Supabase.
-- Correct authorization for removed-but-still-active membership rows.
-- No schema, trigger, financial-envelope, or accounting semantics are changed.

CREATE OR REPLACE FUNCTION public.restore_admin_by_auth_id()
 RETURNS TABLE(membership_id text, local_user_id text, staff_identity_id uuid, business_id text, branch_id text, business_name text, branch_name text, branch_code text, country_code text, currency_code text, currency_decimal_places integer, currency_symbol text, timezone_id text, tax_rounding_mode text, default_tax_rate_bps integer, branch_address text, branch_phone text, tax_registration_number text, display_name text, canonical_phone text, credential_hash text, credential_salt text, permissions jsonb)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    v_user_id uuid;
    v_membership_count bigint;
begin
    v_user_id := (select auth.uid());

    if v_user_id is null then
        raise exception 'Authentication required'
            using errcode = '28000';
    end if;

    -- Require exactly one eligible ADMIN membership.
    -- Never choose an arbitrary tenant.

    select count(*)
    into v_membership_count
    from public.business_memberships bm
    join public.businesses b
      on b.id = bm.business_id
    join public.branches br
      on br.id = bm.branch_id
     and br.business_id = bm.business_id
    join public.profiles p
      on p.auth_user_id = bm.auth_user_id
    where bm.auth_user_id = v_user_id
      and bm.role = 'ADMIN'
      and bm.is_active = true
      and b.is_active = true
      and br.is_active = true
      and p.is_active = true
      and bm.local_user_id is not null
      and btrim(bm.local_user_id) <> '';

    if v_membership_count <> 1 then
        -- No data returned for missing or ambiguous authority.
        return;
    end if;

    return query
    select
        bm.id,
        bm.local_user_id,
        bm.auth_user_id,
        bm.business_id,
        bm.branch_id,
        b.name,
        br.name,
        br.code,
        br.country_code,
        br.currency_code,
        br.currency_decimal_places,
        br.currency_symbol,
        br.timezone_id,
        br.tax_rounding_mode,
        br.default_tax_rate_bps,
        br.address,
        br.phone,
        br.tax_registration_number,
        p.display_name,
        p.canonical_phone,

        -- No authentication secrets leave the server.
        null::text,
        null::text,

        jsonb_build_object(
            'CREATE_SALE', bm.can_create_sale,
            'ACCEPT_CASH', bm.can_accept_cash,
            'ACCEPT_ONLINE', bm.can_accept_online,
            'CREATE_CREDIT_SALE', bm.can_create_credit_sale,
            'USE_COUPON', bm.can_use_coupon,
            'ISSUE_INVOICE', bm.can_issue_invoice,
            'ISSUE_RECEIPT', bm.can_issue_receipt,
            'REFUND', bm.can_refund,
            'VOID', bm.can_void,
            'CORRECT_AMOUNT', bm.can_correct_amount,
            'CHANGE_PAYMENT_METHOD',
                bm.can_change_payment_method,
            'VIEW_BROAD_HISTORY',
                bm.can_view_broad_history,
            'MANAGE_PRODUCTS', bm.can_manage_products,
            'VIEW_REPORTS', bm.can_view_reports
        )
    from public.business_memberships bm
    join public.businesses b
      on b.id = bm.business_id
    join public.branches br
      on br.id = bm.branch_id
     and br.business_id = bm.business_id
    join public.profiles p
      on p.auth_user_id = bm.auth_user_id
    where bm.auth_user_id = v_user_id
      and bm.role = 'ADMIN'
      and bm.is_active = true
      and b.is_active = true
      and br.is_active = true
      and p.is_active = true
      and bm.local_user_id is not null
      and btrim(bm.local_user_id) <> ''
      and bm.can_create_sale is not null
      and bm.can_accept_cash is not null
      and bm.can_accept_online is not null
      and bm.can_create_credit_sale is not null
      and bm.can_use_coupon is not null
      and bm.can_issue_invoice is not null
      and bm.can_issue_receipt is not null
      and bm.can_refund is not null
      and bm.can_void is not null
      and bm.can_correct_amount is not null
      and bm.can_change_payment_method is not null
      and bm.can_view_broad_history is not null
      and bm.can_manage_products is not null
      and bm.can_view_reports is not null;
end;
$function$


ALTER FUNCTION public.restore_admin_by_auth_id() OWNER TO postgres;
REVOKE ALL ON FUNCTION public.restore_admin_by_auth_id() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.restore_admin_by_auth_id() TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.ingest_financial_envelope(p_business_id text, p_operation_id text, p_branch_id text, p_actor_membership_id text, p_device_id text, p_operation_type text, p_schema_version integer, p_occurred_at timestamp with time zone, p_branch_timezone_id text, p_business_local_date date, p_canonical_text text, p_canonical_sha256 text, p_canonical_payload jsonb)
 RETURNS TABLE(status text, operation_id text, canonical_sha256 text, duplicate boolean)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_uid uuid := (select auth.uid());
  v_membership public.business_memberships%rowtype;
  v_existing private.financial_operation_envelopes%rowtype;
  v_inserted_hash text;
  v_field_diff jsonb;
begin
  if v_uid is null then raise exception 'Authentication required' using errcode = '42501'; end if;

  select m.* into v_membership
  from public.business_memberships m
  join public.businesses b on b.id = m.business_id and b.is_active
  join public.branches br on br.id = m.branch_id and br.business_id = m.business_id and br.is_active
  join public.profiles p on p.auth_user_id = m.auth_user_id and p.is_active
  where m.id = p_actor_membership_id and m.auth_user_id = v_uid
    and m.role = 'ADMIN' and m.is_active and m.removed_at IS NULL and m.staff_identity_id is null
    and m.business_id = p_business_id and m.branch_id = p_branch_id;
  if not found then raise exception 'INVALID_ADMIN_MEMBERSHIP' using errcode = '42501'; end if;

  if p_operation_type is null or p_operation_type not in (
    'RECORD_SALE','RECORD_CREDIT_REPAYMENT','RECORD_COUPON_PURCHASE',
    'RECORD_AMOUNT_CORRECTION','RECORD_PAYMENT_METHOD_CHANGE','RECORD_VOID',
    'RECORD_REFUND','RECORD_EXPENSE','RECORD_EXPENSE_CORRECTION',
    'RECORD_EXPENSE_VOID','OPEN_CASH_SHIFT','COUNT_CASH_SHIFT','CLOSE_CASH_SHIFT'
  ) then raise exception 'UNSUPPORTED_OPERATION_TYPE'; end if;

  if p_schema_version is null or p_schema_version < 1
     or p_device_id is null or length(btrim(p_device_id)) not between 1 and 128
     or p_operation_id is null or length(btrim(p_operation_id)) = 0
     or p_canonical_text is null or octet_length(p_canonical_text) not between 2 and 262144
     or p_canonical_sha256 is null or p_canonical_sha256 !~ '^[0-9a-f]{64}$'
     or p_canonical_payload is null or jsonb_typeof(p_canonical_payload) <> 'object'
     or p_occurred_at is null or p_business_local_date is null or p_branch_timezone_id is null
  then raise exception 'INVALID_ENVELOPE'; end if;

  if p_canonical_sha256 <> encode(extensions.digest(convert_to(p_canonical_text, 'UTF8'), 'sha256'), 'hex')
  then raise exception 'DIGEST_MISMATCH'; end if;

  if p_canonical_payload <> p_canonical_text::jsonb
     or p_canonical_payload ->> 'operation_id' is distinct from p_operation_id
     or p_canonical_payload ->> 'business_id' is distinct from p_business_id
  then raise exception 'PAYLOAD_MISMATCH'; end if;

  if p_canonical_payload -> 'branch_id' is distinct from to_jsonb(p_branch_id)
     or p_canonical_payload -> 'device_id' is distinct from to_jsonb(p_device_id)
     or p_canonical_payload -> 'operation_type' is distinct from to_jsonb(p_operation_type)
     or p_canonical_payload -> 'command_schema_version' is distinct from to_jsonb(p_schema_version)
     or p_canonical_payload -> 'occurred_at' is distinct from
          to_jsonb(round(extract(epoch FROM p_occurred_at) * 1000)::bigint)
  then raise exception 'ENVELOPE_METADATA_MISMATCH'; end if;

  if p_occurred_at > now() + interval '10 minutes' then raise exception 'OCCURRED_AT_IN_FUTURE'; end if;

  if not exists (select 1 from public.branches br where br.id = p_branch_id
    and br.business_id = p_business_id and br.timezone_id = p_branch_timezone_id)
  then raise exception 'BRANCH_TIMEZONE_MISMATCH'; end if;

  if p_business_local_date is distinct from (p_occurred_at at time zone p_branch_timezone_id)::date
  then raise exception 'LOCAL_DATE_MISMATCH'; end if;

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

  select e.* into v_existing from private.financial_operation_envelopes e
  where e.business_id = p_business_id and e.operation_id = p_operation_id;

  if v_existing.canonical_sha256 is distinct from p_canonical_sha256 then
    select coalesce(jsonb_object_agg(d.field_name, jsonb_build_object(
      'stored', d.stored_value, 'incoming', d.incoming_value
    )), '{}'::jsonb) into v_field_diff
    from (
      select coalesce(s.key, i.key) as field_name,
             s.value as stored_value, i.value as incoming_value
      from jsonb_each(v_existing.canonical_payload) s
      full join jsonb_each(p_canonical_payload) i on i.key = s.key
      where s.value is distinct from i.value
    ) d;

    insert into private.collision_audit (
      operation_id, operation_type, business_id, branch_id, membership_id, device_id,
      incoming_digest, stored_digest, incoming_canonical_text, stored_canonical_text,
      incoming_payload, stored_payload, field_diff
    ) values (
      p_operation_id, p_operation_type, p_business_id, p_branch_id, p_actor_membership_id, p_device_id,
      p_canonical_sha256, v_existing.canonical_sha256, p_canonical_text, v_existing.canonical_text,
      p_canonical_payload, v_existing.canonical_payload, v_field_diff
    );

    return query select 'OPERATION_COLLISION'::text, p_operation_id, p_canonical_sha256, false;
    return;
  end if;

  return query select 'ACKNOWLEDGED'::text, p_operation_id, p_canonical_sha256, true;
end;
$function$


ALTER FUNCTION public.ingest_financial_envelope(text,text,text,text,text,text,integer,timestamptz,text,date,text,text,jsonb) OWNER TO postgres;
REVOKE ALL ON FUNCTION public.ingest_financial_envelope(text,text,text,text,text,text,integer,timestamptz,text,date,text,text,jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.ingest_financial_envelope(text,text,text,text,text,text,integer,timestamptz,text,date,text,text,jsonb) TO authenticated, service_role;

ALTER POLICY profiles_self_or_shared_business_select ON public.profiles
USING (auth_user_id = (SELECT auth.uid()) OR EXISTS (
  SELECT 1 FROM public.business_memberships target_membership
  WHERE target_membership.auth_user_id = profiles.auth_user_id
    AND target_membership.is_active = true
    AND target_membership.removed_at IS NULL
    AND (SELECT private.is_business_member(target_membership.business_id))
));

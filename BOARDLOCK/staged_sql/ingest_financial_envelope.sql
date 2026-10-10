-- BOARDLOCK STAGED SQL — INGEST FINANCIAL ENVELOPE RPC. DO NOT APPLY OR AUTO-DEPLOY.
create or replace function public.ingest_financial_envelope(
    p_business_id text,
    p_operation_id text,
    p_branch_id text,
    p_actor_membership_id text,
    p_device_id text,
    p_operation_type text,
    p_schema_version integer,
    p_occurred_at timestamptz,
    p_branch_timezone_id text,
    p_business_local_date date,
    p_canonical_text text,
    p_canonical_sha256 text,
    p_canonical_payload jsonb
)
returns table (
    status text,
    operation_id text,
    canonical_sha256 text,
    duplicate boolean
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_uid uuid;
    v_existing_digest text;
    v_membership public.business_memberships%rowtype;
    v_calc_digest text;
begin
    v_uid := auth.uid();

    -- BOARDLOCK: Only the authenticated Admin may ingest until a verified
    -- cashier session credential is available. Membership IDs are NOT credentials.
    if v_uid is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;

    select m.* into v_membership
    from public.business_memberships m
    join public.businesses b on b.id = m.business_id and b.is_active = true
    join public.branches br on br.id = m.branch_id
        and br.business_id = m.business_id and br.is_active = true
    join public.profiles p on p.auth_user_id = m.auth_user_id and p.is_active = true
    where m.id = p_actor_membership_id
      and m.auth_user_id = v_uid
      and m.business_id = p_business_id
      and m.branch_id = p_branch_id
      and m.role = 'ADMIN'
      and m.is_active = true
      and m.staff_identity_id is null;

    if not found then
        raise exception 'Admin authorization required' using errcode = '42501';
    end if;

    -- ----------------------------------------------------------------
    -- 2. CANONICAL SHA-256 DIGEST VERIFICATION
    -- ----------------------------------------------------------------
    if p_canonical_sha256 is null or p_canonical_sha256 !~ '^[0-9a-f]{64}$' then
        raise exception 'Invalid canonical SHA-256 digest format' using errcode = 'P0001';
    end if;

    v_calc_digest := encode(extensions.digest(convert_to(p_canonical_text, 'UTF8'), 'sha256'), 'hex');
    if v_calc_digest != p_canonical_sha256 then
        raise exception 'Canonical digest mismatch: payload tampering detected' using errcode = 'P0001';
    end if;

    -- ----------------------------------------------------------------
    -- 3. IDEMPOTENT INSERTION & CONFLICT HANDLING
    -- ----------------------------------------------------------------
    SELECT e.canonical_sha256 INTO v_existing_digest
    FROM private.financial_operation_envelopes e
    WHERE e.business_id = p_business_id
      AND e.operation_id = p_operation_id;

    if found then
        if v_existing_digest = p_canonical_sha256 then
            return query select 'ACKNOWLEDGED'::text, p_operation_id, p_canonical_sha256, true;
            return;
        else
            raise exception 'Integrity conflict: operation identity mismatch' using errcode = 'P0001';
        end if;
    end if;

    INSERT INTO private.financial_operation_envelopes (
        business_id,
        operation_id,
        branch_id,
        actor_membership_id,
        device_id,
        operation_type,
        schema_version,
        occurred_at,
        branch_timezone_id,
        business_local_date,
        canonical_text,
        canonical_sha256,
        canonical_payload
    ) VALUES (
        p_business_id,
        p_operation_id,
        p_branch_id,
        v_membership.id,
        p_device_id,
        p_operation_type,
        p_schema_version,
        p_occurred_at,
        p_branch_timezone_id,
        p_business_local_date,
        p_canonical_text,
        p_canonical_sha256,
        p_canonical_payload
    );

    return query select 'ACKNOWLEDGED'::text, p_operation_id, p_canonical_sha256, false;
end;
$$;

-- No access for anonymous callers or unknown roles.
revoke all on function public.ingest_financial_envelope(
    text,text,text,text,text,text,integer,timestamptz,text,date,text,text,jsonb
) from public, anon, authenticated, service_role;
grant execute on function public.ingest_financial_envelope(
    text,text,text,text,text,text,integer,timestamptz,text,date,text,text,jsonb
) to authenticated;

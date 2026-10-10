-- BOARDLOCK STAGED SQL — FETCH FINANCIAL ENVELOPE RPC. DO NOT APPLY OR AUTO-DEPLOY.
create or replace function public.fetch_financial_envelopes(
    p_business_id text,
    p_since_received_at timestamptz default '1970-01-01 00:00:00+00'::timestamptz,
    p_limit integer default 100,
    p_actor_membership_id text default null
)
returns table (
    operation_id text,
    business_id text,
    branch_id text,
    operation_type text,
    occurred_at timestamptz,
    received_at timestamptz,
    canonical_text text,
    canonical_payload jsonb
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_uid uuid;
    v_is_valid boolean := false;
begin
    v_uid := auth.uid();

    -- BOARDLOCK: authenticated Admin authorization for this business.
    -- A supplied cashier membership ID is never sufficient proof of identity.
    if v_uid is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    select true into v_is_valid
    from public.business_memberships m
    join public.businesses b on b.id = m.business_id and b.is_active = true
    join public.branches br on br.id = m.branch_id
        and br.business_id = m.business_id and br.is_active = true
    join public.profiles p on p.auth_user_id = m.auth_user_id and p.is_active = true
    where m.business_id = p_business_id
      and m.auth_user_id = v_uid
      and m.role = 'ADMIN'
      and m.is_active = true
      and m.staff_identity_id is null;
    if not coalesce(v_is_valid, false) then
        raise exception 'Admin authorization required' using errcode = '42501';
    end if;

    if p_limit is null or p_limit < 1 or p_limit > 100 then
        raise exception 'Invalid page size' using errcode = '22023';
    end if;

    return query
    SELECT
        e.operation_id,
        e.business_id,
        e.branch_id,
        e.operation_type,
        e.occurred_at,
        e.received_at,
        e.canonical_text,
        e.canonical_payload
    FROM private.financial_operation_envelopes e
    WHERE e.business_id = p_business_id
      AND exists (
          select 1 from public.business_memberships authorized
          where authorized.auth_user_id = v_uid
            and authorized.business_id = e.business_id
            and authorized.branch_id = e.branch_id
            and authorized.role = 'ADMIN'
            and authorized.is_active = true
            and authorized.staff_identity_id is null
      )
      AND e.received_at > p_since_received_at
    ORDER BY e.received_at ASC
    LIMIT p_limit;
end;
$$;

revoke all on function public.fetch_financial_envelopes(text,timestamptz,integer,text)
    from public, anon, authenticated, service_role;
grant execute on function public.fetch_financial_envelopes(text,timestamptz,integer,text)
    to authenticated;

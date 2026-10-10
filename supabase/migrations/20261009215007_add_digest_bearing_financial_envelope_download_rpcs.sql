-- BOARDLOCK STAGED SQL — DIGEST-BEARING DOWNLOAD RPC CONTRACT.
-- DO NOT APPLY OR DEPLOY without separate backend review/approval.
-- New function names avoid changing the existing functions' TABLE return types in place.

create or replace function public.fetch_financial_envelopes_v3(
    p_business_id text,
    p_after_received_at timestamptz default null,
    p_after_operation_id text default null,
    p_limit integer default 100
)
returns table (
    operation_id text,
    business_id text,
    branch_id text,
    operation_type text,
    occurred_at timestamptz,
    received_at timestamptz,
    canonical_text text,
    canonical_payload jsonb,
    canonical_sha256 text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if p_limit is null or p_limit < 1 or p_limit > 100 then
        raise exception 'Invalid page limit' using errcode = '22023';
    end if;
    if (p_after_received_at is null) <> (p_after_operation_id is null) then
        raise exception 'Invalid cursor' using errcode = '22023';
    end if;
    if not exists (
        select 1
        from public.business_memberships m
        join public.businesses b on b.id = m.business_id and b.is_active
        join public.branches br on br.id = m.branch_id
            and br.business_id = m.business_id and br.is_active
        join public.profiles p on p.auth_user_id = m.auth_user_id and p.is_active
        where m.auth_user_id = v_uid
          and m.role = 'ADMIN'
          and m.is_active
          and m.staff_identity_id is null
          and m.business_id = p_business_id
    ) then
        raise exception 'Admin authorization required' using errcode = '42501';
    end if;

    return query
    select e.operation_id, e.business_id, e.branch_id, e.operation_type,
           e.occurred_at, e.received_at, e.canonical_text,
           e.canonical_payload, e.canonical_sha256
    from private.financial_operation_envelopes e
    where e.business_id = p_business_id
      and (
          p_after_received_at is null
          or (e.received_at, e.operation_id) > (p_after_received_at, p_after_operation_id)
      )
      and exists (
          select 1 from public.business_memberships m
          where m.auth_user_id = v_uid
            and m.business_id = e.business_id
            and m.branch_id = e.branch_id
            and m.role = 'ADMIN'
            and m.is_active
            and m.staff_identity_id is null
      )
    order by e.received_at, e.operation_id
    limit p_limit;
end;
$$;

revoke all on function public.fetch_financial_envelopes_v3(text,timestamptz,text,integer)
    from public, anon, authenticated, service_role;
grant execute on function public.fetch_financial_envelopes_v3(text,timestamptz,text,integer)
    to authenticated;

create or replace function public.fetch_cashier_financial_envelopes_v2(
    p_business_id text,
    p_branch_id text,
    p_membership_id text,
    p_after_received_at timestamptz default null,
    p_after_operation_id text default null,
    p_limit integer default 100
)
returns table (
    operation_id text,
    business_id text,
    branch_id text,
    operation_type text,
    occurred_at timestamptz,
    received_at timestamptz,
    canonical_text text,
    canonical_payload jsonb,
    canonical_sha256 text
)
language plpgsql
security definer
set search_path = ''
as $$
begin
    if p_limit is null or p_limit < 1 or p_limit > 100 then
        raise exception 'Invalid page size' using errcode = '22023';
    end if;
    if (p_after_received_at is null) <> (p_after_operation_id is null) then
        raise exception 'Cursor must contain both timestamp and operation id'
            using errcode = '22023';
    end if;
    if not exists (
        select 1
        from public.business_memberships m
        join public.businesses b on b.id = m.business_id and b.is_active
        join public.branches br on br.id = m.branch_id
            and br.business_id = m.business_id and br.is_active
        join public.profiles p on p.auth_user_id = m.auth_user_id and p.is_active
        where m.id = p_membership_id
          and m.business_id = p_business_id
          and m.branch_id = p_branch_id
          and m.role = 'CASHIER'
          and m.is_active
          and m.staff_identity_id is not null
    ) then
        raise exception 'Cashier authorization required' using errcode = '42501';
    end if;

    return query
    select e.operation_id, e.business_id, e.branch_id, e.operation_type,
           e.occurred_at, e.received_at, e.canonical_text,
           e.canonical_payload, e.canonical_sha256
    from private.financial_operation_envelopes e
    where e.business_id = p_business_id
      and e.branch_id = p_branch_id
      and (
          p_after_received_at is null
          or (e.received_at, e.operation_id) > (p_after_received_at, p_after_operation_id)
      )
    order by e.received_at, e.operation_id
    limit p_limit;
end;
$$;

revoke all on function public.fetch_cashier_financial_envelopes_v2(text,text,text,timestamptz,text,integer)
    from public, anon, authenticated, service_role;
grant execute on function public.fetch_cashier_financial_envelopes_v2(text,text,text,timestamptz,text,integer)
    to service_role;

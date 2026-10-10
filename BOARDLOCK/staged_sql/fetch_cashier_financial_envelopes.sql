-- BOARDLOCK STAGED SQL — CASHIER FINANCIAL DOWNLOAD. DO NOT APPLY/AUTO-DEPLOY.
-- The API supplies scope only after verifying the bearer-backed sync session;
-- this RPC independently rechecks active cashier membership and branch scope.
create or replace function public.fetch_cashier_financial_envelopes(
    p_business_id text,
    p_branch_id text,
    p_membership_id text,
    p_after_received_at timestamptz default null,
    p_after_operation_id text default null,
    p_limit integer default 100
)
returns table (
    operation_id text, business_id text, branch_id text, operation_type text,
    occurred_at timestamptz, received_at timestamptz,
    canonical_text text, canonical_payload jsonb
)
language plpgsql security definer set search_path = ''
as $$
begin
    if p_limit is null or p_limit < 1 or p_limit > 100 then
        raise exception 'Invalid page size' using errcode = '22023';
    end if;
    if p_after_received_at is null and p_after_operation_id is not null
       or p_after_received_at is not null and p_after_operation_id is null then
        raise exception 'Cursor must contain both timestamp and operation id' using errcode = '22023';
    end if;
    if not exists (
        select 1 from public.business_memberships m
        join public.businesses b on b.id = m.business_id and b.is_active
        join public.branches br on br.id = m.branch_id and br.business_id = m.business_id and br.is_active
        join public.profiles p on p.auth_user_id = m.auth_user_id and p.is_active
        where m.id = p_membership_id and m.business_id = p_business_id and m.branch_id = p_branch_id
          and m.role = 'CASHIER' and m.is_active and m.staff_identity_id is not null
    ) then raise exception 'Cashier authorization required' using errcode = '42501'; end if;
    return query
    select e.operation_id,e.business_id,e.branch_id,e.operation_type,e.occurred_at,e.received_at,e.canonical_text,e.canonical_payload
    from private.financial_operation_envelopes e
    where e.business_id = p_business_id and e.branch_id = p_branch_id
      and (p_after_received_at is null or (e.received_at, e.operation_id) > (p_after_received_at, p_after_operation_id))
    order by e.received_at asc, e.operation_id asc
    limit p_limit;
end; $$;
revoke all on function public.fetch_cashier_financial_envelopes(text,text,text,timestamptz,text,integer) from public,anon,authenticated;
grant execute on function public.fetch_cashier_financial_envelopes(text,text,text,timestamptz,text,integer) to service_role;

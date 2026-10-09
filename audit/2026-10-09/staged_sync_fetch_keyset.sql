-- STAGED, NOT APPLIED. New function; the existing fetch_financial_envelopes is untouched.
-- Fixes a paging bug: the old function pages by received_at only, so rows sharing the
-- same received_at timestamp across a page boundary can be skipped forever.
-- The caller passes back the last (received_at, operation_id) it saw.
create or replace function public.fetch_financial_envelopes_v2(
  p_business_id text,
  p_after_received_at timestamptz default '1970-01-01 00:00:00+00',
  p_after_operation_id text default '',
  p_limit integer default 100
)
returns table(
  operation_id text, business_id text, branch_id text, operation_type text,
  occurred_at timestamptz, received_at timestamptz,
  canonical_text text, canonical_payload jsonb
)
language plpgsql security definer set search_path = ''
as $function$
declare v_uid uuid := (select auth.uid());
begin
  if v_uid is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_limit is null or p_limit not between 1 and 100 then
    raise exception 'Invalid limit' using errcode='22023';
  end if;
  if not exists (
    select 1 from public.business_memberships m
    join public.businesses b on b.id=m.business_id and b.is_active
    join public.branches br on br.id=m.branch_id and br.business_id=m.business_id and br.is_active
    join public.profiles p on p.auth_user_id=m.auth_user_id and p.is_active
    where m.auth_user_id=v_uid and m.role='ADMIN' and m.is_active
      and m.staff_identity_id is null and m.business_id=p_business_id
  ) then raise exception 'Admin authorization required' using errcode='42501'; end if;

  return query
  select e.operation_id,e.business_id,e.branch_id,e.operation_type,e.occurred_at,
         e.received_at,e.canonical_text,e.canonical_payload
  from private.financial_operation_envelopes e
  where e.business_id=p_business_id
    and (e.received_at, e.operation_id) > (p_after_received_at, p_after_operation_id)
    and exists (
      select 1 from public.business_memberships m
      where m.auth_user_id=v_uid and m.business_id=e.business_id and m.branch_id=e.branch_id
        and m.role='ADMIN' and m.is_active and m.staff_identity_id is null
    )
  order by e.received_at, e.operation_id
  limit p_limit;
end $function$;

revoke execute on function public.fetch_financial_envelopes_v2(text,timestamptz,text,integer) from public, anon;
grant  execute on function public.fetch_financial_envelopes_v2(text,timestamptz,text,integer) to authenticated;

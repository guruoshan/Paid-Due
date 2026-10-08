-- REVIEWED AGAINST THE LIVE SCHEMA ON 2026-10-08.
-- STAGED ONLY: do not apply without explicit deployment approval.
--
-- Read-only employee directory for the authenticated Admin's authorized
-- business/branch memberships. PIN-only cashier identities are sourced from
-- private.staff_identities through business_memberships.staff_identity_id.
-- Credential hashes and salts are intentionally never selected or returned.

create or replace function public.list_admin_cashiers()
returns table (
    local_user_id text,
    membership_id text,
    branch_id text,
    display_name text,
    canonical_phone text,
    is_active boolean
)
language plpgsql
security definer
set search_path = ''
stable
as $$
declare
    v_auth_user_id uuid := (select auth.uid());
begin
    if v_auth_user_id is null then
        raise exception 'Authentication required'
            using errcode = '42501';
    end if;

    return query
    select
        cashier.local_user_id,
        cashier.id as membership_id,
        cashier.branch_id,
        staff.display_name,
        staff.canonical_phone,
        (cashier.is_active and staff.is_active) as is_active
    from public.business_memberships as admin_membership
    join public.profiles as admin_profile
      on admin_profile.auth_user_id = admin_membership.auth_user_id
     and admin_profile.is_active = true
    join public.businesses as business
      on business.id = admin_membership.business_id
     and business.is_active = true
    join public.branches as branch
      on branch.id = admin_membership.branch_id
     and branch.business_id = admin_membership.business_id
     and branch.is_active = true
    join public.business_memberships as cashier
      on cashier.business_id = admin_membership.business_id
     and cashier.branch_id = admin_membership.branch_id
     and cashier.role = 'CASHIER'
    join private.staff_identities as staff
      on staff.id = cashier.staff_identity_id
    where admin_membership.auth_user_id = v_auth_user_id
      and admin_membership.role = 'ADMIN'
      and admin_membership.is_active = true
      and admin_membership.staff_identity_id is null
      and cashier.auth_user_id is null
      and cashier.staff_identity_id is not null
      and cashier.local_user_id is not null
      and btrim(cashier.local_user_id) <> ''
    order by staff.display_name, cashier.local_user_id;
end;
$$;

comment on function public.list_admin_cashiers() is
    'Returns essential PIN-only cashier directory fields for branches where auth.uid() has an active ADMIN membership.';

revoke all on function public.list_admin_cashiers() from public;
revoke all on function public.list_admin_cashiers() from anon;
revoke all on function public.list_admin_cashiers() from authenticated;
revoke all on function public.list_admin_cashiers() from service_role;
grant execute on function public.list_admin_cashiers() to authenticated;

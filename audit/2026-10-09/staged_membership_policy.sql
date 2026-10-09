-- STAGED, NOT APPLIED. Review, then apply to Supabase project ttvdpgxqvvtjnxxfkoui.
-- Limits who can read business_memberships rows (which include permission flags):
-- an admin of the business, or the row's own user.
begin;
drop policy if exists memberships_same_business_select on public.business_memberships;
create policy memberships_self_or_admin_select on public.business_memberships
  for select to authenticated
  using (
    auth_user_id = (select auth.uid())
    or (select private.is_business_admin(business_id))
  );
commit;
-- Check first that the app never reads other users' memberships as a non-admin.

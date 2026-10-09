-- STAGED, NOT APPLIED. Run ONLY after the app is updated to call
-- POST /api/staff/preview on the worker instead of Supabase RPC directly.
-- Running it earlier breaks invite preview for every installed app version.
revoke execute on function public.preview_staff_invite(text) from anon, authenticated;
grant  execute on function public.preview_staff_invite(text) to service_role;

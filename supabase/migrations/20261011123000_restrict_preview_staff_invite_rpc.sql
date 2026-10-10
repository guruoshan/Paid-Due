-- The public staff-preview Edge Function calls this RPC with the service-role key.
-- Client roles must not call the SECURITY DEFINER function directly.
BEGIN;

REVOKE ALL ON FUNCTION public.preview_staff_invite(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.preview_staff_invite(text) FROM anon;
REVOKE ALL ON FUNCTION public.preview_staff_invite(text) FROM authenticated;
GRANT EXECUTE ON FUNCTION public.preview_staff_invite(text) TO service_role;

COMMIT;

-- Trigger-only SECURITY DEFINER functions are invoked by PostgreSQL's trigger
-- mechanism, not as caller-invoked RPCs. Remove direct invocation grants while
-- preserving their attached triggers and the owner's ability to maintain them.
BEGIN;

REVOKE EXECUTE ON FUNCTION private.notify_admins_of_confirmed_day_close()
  FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION private.notify_admins_of_confirmed_day_close()
  FROM anon;
REVOKE EXECUTE ON FUNCTION private.notify_admins_of_confirmed_day_close()
  FROM authenticated;

REVOKE EXECUTE ON FUNCTION private.notify_cashier_of_permission_change()
  FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION private.notify_cashier_of_permission_change()
  FROM anon;
REVOKE EXECUTE ON FUNCTION private.notify_cashier_of_permission_change()
  FROM authenticated;

COMMIT;

-- TASK 3D — Proposal only. DO NOT APPLY until staging verifies session revocation and cleanup.
-- Add indexes supporting membership-wide revocation and expiry cleanup. Provide a service-role-only
-- cleanup function, but deliberately do not schedule it; operational scheduling is a separate decision.
--
-- Rollback:
--   DROP FUNCTION IF EXISTS public.prune_expired_cashier_sync_sessions();
--   DROP INDEX IF EXISTS private.idx_cashier_sync_sessions_active_expiry;
--   DROP INDEX IF EXISTS private.idx_cashier_sync_sessions_active_membership;
-- Calling the cleanup function permanently removes only expired or already-revoked auth tokens.
-- It does not delete financial envelopes or membership history. Rollback cannot restore deleted token rows.

BEGIN;

CREATE INDEX IF NOT EXISTS idx_cashier_sync_sessions_active_expiry
  ON private.cashier_sync_sessions (expires_at)
  WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_cashier_sync_sessions_active_membership
  ON private.cashier_sync_sessions (membership_id, expires_at)
  WHERE revoked_at IS NULL;

CREATE OR REPLACE FUNCTION public.prune_expired_cashier_sync_sessions()
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_deleted bigint;
BEGIN
  DELETE FROM private.cashier_sync_sessions
  WHERE expires_at <= pg_catalog.now()
     OR revoked_at IS NOT NULL;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  RETURN v_deleted;
END;
$function$;

REVOKE ALL ON FUNCTION public.prune_expired_cashier_sync_sessions()
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.prune_expired_cashier_sync_sessions()
  TO service_role;

COMMIT;

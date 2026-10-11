-- TASK 3C — Proposal only. DO NOT APPLY until audit-write paths are tested in staging.
-- Prevent accidental UPDATE, DELETE, or TRUNCATE of the staff admin audit trail.
-- Existing audited permission/removal RPCs INSERT rows; they do not need mutation privileges.
--
-- Rollback:
--   DROP TRIGGER IF EXISTS trg_staff_admin_audit_no_row_mutation ON private.staff_admin_audit_events;
--   DROP TRIGGER IF EXISTS trg_staff_admin_audit_no_truncate ON private.staff_admin_audit_events;
--   DROP FUNCTION IF EXISTS private.reject_staff_admin_audit_mutation();
-- No existing audit rows are changed. If an emergency requires editing/deleting audit data,
-- use a separately approved, audited break-glass procedure rather than silently disabling this guard.

CREATE OR REPLACE FUNCTION private.reject_staff_admin_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path TO ''
AS $function$
BEGIN
  RAISE EXCEPTION 'STAFF_ADMIN_AUDIT_EVENTS_APPEND_ONLY'
    USING ERRCODE = '42501';
  RETURN NULL;
END;
$function$;

DROP TRIGGER IF EXISTS trg_staff_admin_audit_no_row_mutation
  ON private.staff_admin_audit_events;
CREATE TRIGGER trg_staff_admin_audit_no_row_mutation
BEFORE UPDATE OR DELETE ON private.staff_admin_audit_events
FOR EACH ROW
EXECUTE FUNCTION private.reject_staff_admin_audit_mutation();

DROP TRIGGER IF EXISTS trg_staff_admin_audit_no_truncate
  ON private.staff_admin_audit_events;
CREATE TRIGGER trg_staff_admin_audit_no_truncate
BEFORE TRUNCATE ON private.staff_admin_audit_events
FOR EACH STATEMENT
EXECUTE FUNCTION private.reject_staff_admin_audit_mutation();


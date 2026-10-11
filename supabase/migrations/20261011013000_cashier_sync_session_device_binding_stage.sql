-- TASK 3E — Proposal only. DO NOT APPLY until the Edge API is updated to use v2 functions
-- and Android sends a persistent random device-session ID. These additive v2 RPCs are not used
-- by the current app/API and therefore do not change current behavior on their own.
--
-- Device ID is a session binding, not hardware attestation or a substitute for the bearer token.
-- Rollback is safe only before any deployed client/API relies on v2. If populated device_id values
-- are operationally relied upon, preserve/export them before dropping the column.
--
-- Rollback:
--   DROP FUNCTION IF EXISTS public.verify_cashier_sync_session_v2(text,text);
--   DROP FUNCTION IF EXISTS public.create_cashier_sync_session_v2(text,text,text);
--   DROP INDEX IF EXISTS private.idx_cashier_sync_sessions_device_active;
--   ALTER TABLE private.cashier_sync_sessions DROP CONSTRAINT IF EXISTS cashier_sync_sessions_device_id_format;
--   ALTER TABLE private.cashier_sync_sessions DROP COLUMN IF EXISTS device_id;

BEGIN;

ALTER TABLE private.cashier_sync_sessions
  ADD COLUMN IF NOT EXISTS device_id text;

ALTER TABLE private.cashier_sync_sessions
  DROP CONSTRAINT IF EXISTS cashier_sync_sessions_device_id_format;
ALTER TABLE private.cashier_sync_sessions
  ADD CONSTRAINT cashier_sync_sessions_device_id_format
  CHECK (device_id IS NULL OR device_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
  NOT VALID;
ALTER TABLE private.cashier_sync_sessions
  VALIDATE CONSTRAINT cashier_sync_sessions_device_id_format;

CREATE INDEX IF NOT EXISTS idx_cashier_sync_sessions_device_active
  ON private.cashier_sync_sessions (membership_id, device_id, expires_at)
  WHERE revoked_at IS NULL AND device_id IS NOT NULL;

CREATE OR REPLACE FUNCTION public.create_cashier_sync_session_v2(
  p_membership_id text,
  p_token_hash text,
  p_device_id text
)
RETURNS TABLE(session_id uuid, expires_at timestamptz)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
BEGIN
  IF p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$'
     OR p_device_id IS NULL OR p_device_id !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$' THEN
    RAISE EXCEPTION 'INVALID_SESSION_METADATA' USING ERRCODE = '22023';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.business_memberships m
    JOIN private.staff_identities si ON si.id = m.staff_identity_id AND si.is_active
    JOIN public.businesses b ON b.id = m.business_id AND b.is_active
    JOIN public.branches br ON br.id = m.branch_id AND br.business_id = m.business_id AND br.is_active
    WHERE m.id = p_membership_id
      AND m.role = 'CASHIER'
      AND m.is_active
      AND m.removed_at IS NULL
  ) THEN
    RAISE EXCEPTION 'INVALID_MEMBERSHIP' USING ERRCODE = '42501';
  END IF;

  RETURN QUERY
  INSERT INTO private.cashier_sync_sessions(membership_id, token_hash, device_id, expires_at)
  VALUES (p_membership_id, p_token_hash, p_device_id, pg_catalog.now() + interval '12 hours')
  RETURNING id, cashier_sync_sessions.expires_at;
END;
$function$;

CREATE OR REPLACE FUNCTION public.verify_cashier_sync_session_v2(
  p_token_hash text,
  p_device_id text
)
RETURNS TABLE(membership_id text, business_id text, branch_id text)
LANGUAGE sql
SECURITY DEFINER
SET search_path TO ''
AS $function$
  SELECT m.id, m.business_id, m.branch_id
  FROM private.cashier_sync_sessions s
  JOIN public.business_memberships m ON m.id = s.membership_id
  JOIN private.staff_identities si ON si.id = m.staff_identity_id AND si.is_active
  JOIN public.businesses b ON b.id = m.business_id AND b.is_active
  JOIN public.branches br ON br.id = m.branch_id
    AND br.business_id = m.business_id AND br.is_active
  WHERE s.token_hash = p_token_hash
    AND s.device_id = p_device_id
    AND s.expires_at > pg_catalog.now()
    AND s.revoked_at IS NULL
    AND m.is_active
    AND m.removed_at IS NULL
    AND m.role = 'CASHIER'
  LIMIT 1;
$function$;

REVOKE ALL ON FUNCTION public.create_cashier_sync_session_v2(text,text,text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.create_cashier_sync_session_v2(text,text,text)
  TO service_role;
REVOKE ALL ON FUNCTION public.verify_cashier_sync_session_v2(text,text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.verify_cashier_sync_session_v2(text,text)
  TO service_role;

COMMIT;

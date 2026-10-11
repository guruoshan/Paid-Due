-- TASK 3B — proposed only; NOT APPLIED.
-- Rollback note: restore the previous create_cashier_sync_session(text,text)
-- definition captured in ROLLBACK_NOTES.md; do not DROP this function because
-- the deployed API depends on its exact signature/result shape.
CREATE OR REPLACE FUNCTION public.create_cashier_sync_session(
  p_membership_id text,
  p_token_hash text
)
RETURNS TABLE(session_id uuid, expires_at timestamptz)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_membership public.business_memberships%ROWTYPE;
BEGIN
  IF p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$' THEN
    RAISE EXCEPTION 'INVALID_TOKEN_HASH' USING ERRCODE = '22023';
  END IF;

  SELECT m.* INTO v_membership
  FROM public.business_memberships m
  JOIN private.staff_identities s ON s.id = m.staff_identity_id AND s.is_active
  JOIN public.businesses b ON b.id = m.business_id AND b.is_active
  JOIN public.branches br ON br.id = m.branch_id AND br.business_id = m.business_id AND br.is_active
  WHERE m.id = p_membership_id
    AND m.role = 'CASHIER'
    AND m.is_active
    AND m.removed_at IS NULL
  FOR UPDATE OF m;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'INVALID_MEMBERSHIP' USING ERRCODE = '42501';
  END IF;

  -- Proposed single-active-session policy. This will log out the same cashier
  -- on every other device; see TASK 3B in the proposal notes before applying.
  UPDATE private.cashier_sync_sessions
  SET revoked_at = COALESCE(revoked_at, now())
  WHERE membership_id = p_membership_id
    AND revoked_at IS NULL
    AND expires_at > now();

  RETURN QUERY
  INSERT INTO private.cashier_sync_sessions (membership_id, token_hash, expires_at)
  VALUES (p_membership_id, p_token_hash, now() + interval '12 hours')
  RETURNING id, cashier_sync_sessions.expires_at;
END;
$function$;

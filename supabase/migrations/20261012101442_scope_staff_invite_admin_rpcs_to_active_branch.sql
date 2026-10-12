-- Limit staff-invite listing and revocation to active Admin membership in the
-- exact branch. Use an empty search_path and schema-qualify every object.
CREATE OR REPLACE FUNCTION public.list_staff_invites(p_business_id text)
RETURNS TABLE(
  invite_id uuid,
  branch_id text,
  branch_name text,
  display_name text,
  canonical_phone text,
  role text,
  permissions jsonb,
  created_at timestamptz,
  expires_at timestamptz,
  used_at timestamptz,
  revoked_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_auth_user_id uuid := (SELECT auth.uid());
BEGIN
  IF v_auth_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;

  IF p_business_id IS NULL OR btrim(p_business_id) = '' THEN
    RAISE EXCEPTION 'Business is required' USING ERRCODE = '22023';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.business_memberships bm
    JOIN public.profiles profile
      ON profile.auth_user_id = bm.auth_user_id
     AND profile.is_active = true
    JOIN public.businesses business
      ON business.id = bm.business_id
     AND business.is_active = true
    JOIN public.branches branch
      ON branch.id = bm.branch_id
     AND branch.business_id = bm.business_id
     AND branch.is_active = true
    WHERE bm.auth_user_id = v_auth_user_id
      AND bm.business_id = p_business_id
      AND bm.role = 'ADMIN'
      AND bm.is_active = true
      AND bm.removed_at IS NULL
      AND bm.staff_identity_id IS NULL
  ) THEN
    RAISE EXCEPTION 'Active branch Admin authority required'
      USING ERRCODE = '42501';
  END IF;

  RETURN QUERY
  SELECT
    i.id, i.branch_id, br.name, i.display_name, i.canonical_phone,
    i.role, i.permissions, i.created_at, i.expires_at, i.used_at, i.revoked_at
  FROM private.staff_invites i
  JOIN public.businesses b
    ON b.id = i.business_id AND b.is_active = true
  JOIN public.branches br
    ON br.id = i.branch_id
   AND br.business_id = i.business_id
   AND br.is_active = true
  JOIN public.business_memberships admin
    ON admin.auth_user_id = v_auth_user_id
   AND admin.business_id = i.business_id
   AND admin.branch_id = i.branch_id
   AND admin.role = 'ADMIN'
   AND admin.is_active = true
   AND admin.removed_at IS NULL
   AND admin.staff_identity_id IS NULL
  JOIN public.profiles profile
    ON profile.auth_user_id = admin.auth_user_id
   AND profile.is_active = true
  WHERE i.business_id = p_business_id
  ORDER BY i.created_at DESC;
END;
$function$;

CREATE OR REPLACE FUNCTION public.revoke_staff_invite(p_invite_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_auth_user_id uuid := (SELECT auth.uid());
  v_business_id text;
  v_branch_id text;
BEGIN
  IF v_auth_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;

  SELECT i.business_id, i.branch_id
    INTO v_business_id, v_branch_id
  FROM private.staff_invites i
  WHERE i.id = p_invite_id;

  IF v_business_id IS NULL OR v_branch_id IS NULL THEN
    RETURN false;
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.business_memberships admin
    JOIN public.profiles profile
      ON profile.auth_user_id = admin.auth_user_id
     AND profile.is_active = true
    JOIN public.businesses business
      ON business.id = admin.business_id
     AND business.is_active = true
    JOIN public.branches branch
      ON branch.id = admin.branch_id
     AND branch.business_id = admin.business_id
     AND branch.is_active = true
    WHERE admin.auth_user_id = v_auth_user_id
      AND admin.business_id = v_business_id
      AND admin.branch_id = v_branch_id
      AND admin.role = 'ADMIN'
      AND admin.is_active = true
      AND admin.removed_at IS NULL
      AND admin.staff_identity_id IS NULL
  ) THEN
    RAISE EXCEPTION 'Active branch Admin authority required'
      USING ERRCODE = '42501';
  END IF;

  UPDATE private.staff_invites i
     SET revoked_at = COALESCE(i.revoked_at, pg_catalog.now())
   WHERE i.id = p_invite_id
     AND i.used_at IS NULL;

  RETURN FOUND;
END;
$function$;

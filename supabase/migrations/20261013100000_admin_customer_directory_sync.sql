-- Authenticated Admin access to the business-scoped customer directory.
-- The caller supplies the target branch explicitly, but the database verifies that
-- auth.uid() has an active ADMIN membership for that exact business and branch.
CREATE OR REPLACE FUNCTION public.sync_customer_directory_admin_upsert(
  p_business_id text,
  p_branch_id text,
  p_customer_id text,
  p_canonical_phone text,
  p_display_name text,
  p_created_at timestamptz DEFAULT now()
)
RETURNS TABLE(
  customer_id text,
  canonical_phone text,
  display_name text,
  created_at timestamptz,
  updated_at timestamptz,
  was_created boolean
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_membership_id text;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;
  SELECT m.id INTO v_membership_id
  FROM public.business_memberships m
  JOIN public.profiles p ON p.auth_user_id = m.auth_user_id AND p.is_active = true
  JOIN public.businesses b ON b.id = m.business_id AND b.is_active = true
  JOIN public.branches br ON br.id = m.branch_id AND br.business_id = m.business_id AND br.is_active = true
  WHERE m.auth_user_id = auth.uid()
    AND m.business_id = p_business_id
    AND m.branch_id = p_branch_id
    AND m.role = 'ADMIN'
    AND m.is_active = true
    AND m.removed_at IS NULL
    AND m.staff_identity_id IS NULL
  LIMIT 1;
  IF v_membership_id IS NULL THEN
    RAISE EXCEPTION 'Admin authority required for requested business and branch' USING ERRCODE = '42501';
  END IF;
  RETURN QUERY
  SELECT d.customer_id, d.canonical_phone, d.display_name, d.created_at, d.updated_at, d.was_created
  FROM public.sync_customer_directory_upsert(
    p_business_id, p_branch_id, v_membership_id, p_customer_id,
    p_canonical_phone, p_display_name, p_created_at
  ) d;
END;
$function$;

CREATE OR REPLACE FUNCTION public.sync_customer_directory_admin_list(
  p_business_id text,
  p_branch_id text,
  p_after_updated_at timestamptz DEFAULT NULL,
  p_limit integer DEFAULT 100
)
RETURNS TABLE(
  customer_id text,
  canonical_phone text,
  display_name text,
  created_at timestamptz,
  updated_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_membership_id text;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;
  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 500 THEN
    RAISE EXCEPTION 'INVALID_CUSTOMER_SYNC_REQUEST' USING ERRCODE = '22023';
  END IF;
  SELECT m.id INTO v_membership_id
  FROM public.business_memberships m
  JOIN public.profiles p ON p.auth_user_id = m.auth_user_id AND p.is_active = true
  JOIN public.businesses b ON b.id = m.business_id AND b.is_active = true
  JOIN public.branches br ON br.id = m.branch_id AND br.business_id = m.business_id AND br.is_active = true
  WHERE m.auth_user_id = auth.uid()
    AND m.business_id = p_business_id
    AND m.branch_id = p_branch_id
    AND m.role = 'ADMIN'
    AND m.is_active = true
    AND m.removed_at IS NULL
    AND m.staff_identity_id IS NULL
  LIMIT 1;
  IF v_membership_id IS NULL THEN
    RAISE EXCEPTION 'Admin authority required for requested business and branch' USING ERRCODE = '42501';
  END IF;
  RETURN QUERY
  SELECT d.customer_id, d.canonical_phone, d.display_name, d.created_at, d.updated_at
  FROM public.sync_customer_directory_list(
    p_business_id, p_branch_id, v_membership_id, p_after_updated_at, p_limit
  ) d;
END;
$function$;

REVOKE ALL ON FUNCTION public.sync_customer_directory_admin_upsert(text,text,text,text,text,timestamptz) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.sync_customer_directory_admin_list(text,text,timestamptz,integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.sync_customer_directory_admin_upsert(text,text,text,text,text,timestamptz) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_customer_directory_admin_list(text,text,timestamptz,integer) TO authenticated;

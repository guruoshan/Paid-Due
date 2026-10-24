-- Repair admin customer-directory sync after the legacy cashier-capable RPCs were retired.
-- Keep the admin-only authorization boundary; access the private table directly from SECURITY DEFINER RPCs.
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
  v_inserted integer := 0;
  v_row private.customer_directory%rowtype;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;
  IF p_business_id IS NULL OR p_branch_id IS NULL
     OR p_customer_id IS NULL OR length(btrim(p_customer_id)) NOT BETWEEN 1 AND 128
     OR p_canonical_phone IS NULL OR p_canonical_phone !~ '^\+[1-9][0-9]{7,14}$'
     OR p_display_name IS NULL OR length(btrim(p_display_name)) NOT BETWEEN 1 AND 200 THEN
    RAISE EXCEPTION 'INVALID_CUSTOMER' USING ERRCODE = '22023';
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

  INSERT INTO private.customer_directory (
    business_id, canonical_phone, customer_id, display_name,
    created_at, updated_at, created_by_membership_id, updated_by_membership_id
  ) VALUES (
    p_business_id, p_canonical_phone, btrim(p_customer_id), btrim(p_display_name),
    coalesce(p_created_at, now()), now(), v_membership_id, v_membership_id
  )
  ON CONFLICT ON CONSTRAINT customer_directory_pkey DO NOTHING;
  GET DIAGNOSTICS v_inserted = ROW_COUNT;

  SELECT d.* INTO v_row
  FROM private.customer_directory d
  WHERE d.business_id = p_business_id
    AND d.canonical_phone = p_canonical_phone;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'CUSTOMER_DIRECTORY_WRITE_NOT_VISIBLE' USING ERRCODE = 'P0002';
  END IF;

  RETURN QUERY SELECT v_row.customer_id, v_row.canonical_phone, v_row.display_name,
    v_row.created_at, v_row.updated_at, (v_inserted > 0);
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
  FROM private.customer_directory d
  WHERE d.business_id = p_business_id
    AND (p_after_updated_at IS NULL OR d.updated_at >= p_after_updated_at)
  ORDER BY d.updated_at ASC, d.canonical_phone ASC
  LIMIT p_limit;
END;
$function$;

REVOKE ALL ON FUNCTION public.sync_customer_directory_admin_upsert(text,text,text,text,text,timestamptz) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.sync_customer_directory_admin_list(text,text,timestamptz,integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.sync_customer_directory_admin_upsert(text,text,text,text,text,timestamptz) TO authenticated;
GRANT EXECUTE ON FUNCTION public.sync_customer_directory_admin_list(text,text,timestamptz,integer) TO authenticated;

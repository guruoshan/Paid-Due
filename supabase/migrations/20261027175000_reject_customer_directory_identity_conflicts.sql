-- Reject customer identity conflicts instead of silently returning another customer ID.
-- Customer identity is unique by (business_id, canonical_phone) and (business_id, customer_id).
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

  BEGIN
    INSERT INTO private.customer_directory (
      business_id, canonical_phone, customer_id, display_name,
      created_at, updated_at, created_by_membership_id, updated_by_membership_id
    ) VALUES (
      p_business_id, p_canonical_phone, btrim(p_customer_id), btrim(p_display_name),
      coalesce(p_created_at, now()), now(), v_membership_id, v_membership_id
    )
    ON CONFLICT ON CONSTRAINT customer_directory_pkey DO NOTHING;
    GET DIAGNOSTICS v_inserted = ROW_COUNT;
  EXCEPTION WHEN unique_violation THEN
    -- The other uniqueness constraint is (business_id, customer_id): the same
    -- customer ID cannot identify a different phone within the same business.
    RAISE EXCEPTION 'CUSTOMER_IDENTITY_CONFLICT' USING ERRCODE = '23505';
  END;

  SELECT d.* INTO v_row
  FROM private.customer_directory d
  WHERE d.business_id = p_business_id
    AND d.canonical_phone = p_canonical_phone
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'CUSTOMER_DIRECTORY_WRITE_NOT_VISIBLE' USING ERRCODE = 'P0002';
  END IF;

  IF v_row.customer_id <> btrim(p_customer_id) THEN
    RAISE EXCEPTION 'CUSTOMER_IDENTITY_CONFLICT' USING ERRCODE = '23505';
  END IF;

  -- Matching retries are idempotent and never overwrite canonical identity or
  -- server-owned display/audit fields. Conflicting IDs are explicit failures.
  RETURN QUERY SELECT v_row.customer_id, v_row.canonical_phone, v_row.display_name,
    v_row.created_at, v_row.updated_at, (v_inserted > 0);
END;
$function$;

REVOKE ALL ON FUNCTION public.sync_customer_directory_admin_upsert(text,text,text,text,text,timestamptz) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.sync_customer_directory_admin_upsert(text,text,text,text,text,timestamptz) TO authenticated;

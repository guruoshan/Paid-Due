-- Tighten authorization for legacy authenticated cashier-permission and branch-update RPCs.
CREATE OR REPLACE FUNCTION public.update_cashier_permissions(
  p_membership_id text,
  p_permissions jsonb
) RETURNS TABLE(membership_id text, updated_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path TO ''
AS $function$
DECLARE
  v_uid uuid := (SELECT auth.uid());
  v_target public.business_memberships%ROWTYPE;
  v_updated timestamptz;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501'; END IF;
  IF p_membership_id IS NULL OR btrim(p_membership_id) = ''
     OR p_permissions IS NULL OR pg_catalog.jsonb_typeof(p_permissions) <> 'object' THEN
    RAISE EXCEPTION 'Invalid permissions update' USING ERRCODE = '22023';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_catalog.jsonb_object_keys(p_permissions) AS k(key)
    WHERE k.key NOT IN ('CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE','USE_COUPON',
      'ISSUE_INVOICE','ISSUE_RECEIPT','REFUND','VOID','CORRECT_AMOUNT','CHANGE_PAYMENT_METHOD',
      'VIEW_BROAD_HISTORY','MANAGE_PRODUCTS','VIEW_REPORTS')
  ) OR EXISTS (
    SELECT 1 FROM pg_catalog.jsonb_each(p_permissions) AS e(key,value)
    WHERE pg_catalog.jsonb_typeof(e.value) <> 'boolean'
  ) THEN
    RAISE EXCEPTION 'Unknown permission or non-boolean value' USING ERRCODE = '22023';
  END IF;

  SELECT m.* INTO v_target
  FROM public.business_memberships m
  JOIN private.staff_identities si ON si.id = m.staff_identity_id AND si.is_active = true
  JOIN public.businesses b ON b.id = m.business_id AND b.is_active = true
  JOIN public.branches br ON br.id = m.branch_id AND br.business_id = m.business_id AND br.is_active = true
  WHERE m.id = p_membership_id AND m.role = 'CASHIER'
    AND m.is_active = true AND m.removed_at IS NULL AND m.staff_identity_id IS NOT NULL
  FOR UPDATE OF m;
  IF NOT FOUND THEN RAISE EXCEPTION 'Active cashier membership not found' USING ERRCODE = 'P0002'; END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.business_memberships a
    JOIN public.profiles p ON p.auth_user_id = a.auth_user_id AND p.is_active = true
    JOIN public.businesses b ON b.id = a.business_id AND b.is_active = true
    JOIN public.branches br ON br.id = a.branch_id AND br.business_id = a.business_id AND br.is_active = true
    WHERE a.auth_user_id = v_uid AND a.business_id = v_target.business_id
      AND a.branch_id = v_target.branch_id AND a.role = 'ADMIN'
      AND a.is_active = true AND a.removed_at IS NULL AND a.staff_identity_id IS NULL
  ) THEN RAISE EXCEPTION 'Active Admin authority required for this branch' USING ERRCODE = '42501'; END IF;

  UPDATE public.business_memberships m SET
    can_create_sale = COALESCE((p_permissions->>'CREATE_SALE')::boolean,m.can_create_sale),
    can_accept_cash = COALESCE((p_permissions->>'ACCEPT_CASH')::boolean,m.can_accept_cash),
    can_accept_online = COALESCE((p_permissions->>'ACCEPT_ONLINE')::boolean,m.can_accept_online),
    can_create_credit_sale = COALESCE((p_permissions->>'CREATE_CREDIT_SALE')::boolean,m.can_create_credit_sale),
    can_use_coupon = COALESCE((p_permissions->>'USE_COUPON')::boolean,m.can_use_coupon),
    can_issue_invoice = COALESCE((p_permissions->>'ISSUE_INVOICE')::boolean,m.can_issue_invoice),
    can_issue_receipt = COALESCE((p_permissions->>'ISSUE_RECEIPT')::boolean,m.can_issue_receipt),
    can_refund = COALESCE((p_permissions->>'REFUND')::boolean,m.can_refund),
    can_void = COALESCE((p_permissions->>'VOID')::boolean,m.can_void),
    can_correct_amount = COALESCE((p_permissions->>'CORRECT_AMOUNT')::boolean,m.can_correct_amount),
    can_change_payment_method = COALESCE((p_permissions->>'CHANGE_PAYMENT_METHOD')::boolean,m.can_change_payment_method),
    can_view_broad_history = COALESCE((p_permissions->>'VIEW_BROAD_HISTORY')::boolean,m.can_view_broad_history),
    can_manage_products = COALESCE((p_permissions->>'MANAGE_PRODUCTS')::boolean,m.can_manage_products),
    can_view_reports = COALESCE((p_permissions->>'VIEW_REPORTS')::boolean,m.can_view_reports),
    updated_at = pg_catalog.now()
  WHERE m.id = v_target.id
  RETURNING m.updated_at INTO v_updated;
  RETURN QUERY SELECT v_target.id, v_updated;
END;
$function$;

CREATE OR REPLACE FUNCTION public.update_branch_details(
  p_business_id text, p_branch_id text, p_business_name text DEFAULT NULL,
  p_address text DEFAULT NULL, p_phone text DEFAULT NULL,
  p_tax_registration_number text DEFAULT NULL
) RETURNS TABLE(business_id text, branch_id text, updated_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path TO ''
AS $function$
DECLARE
  v_uid uuid := (SELECT auth.uid());
  v_updated timestamptz;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501'; END IF;
  IF p_business_id IS NULL OR btrim(p_business_id) = ''
     OR p_branch_id IS NULL OR btrim(p_branch_id) = '' THEN
    RAISE EXCEPTION 'Business and branch are required' USING ERRCODE = '22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.business_memberships a
    JOIN public.profiles p ON p.auth_user_id = a.auth_user_id AND p.is_active = true
    JOIN public.businesses b ON b.id = a.business_id AND b.is_active = true
    JOIN public.branches br ON br.id = a.branch_id AND br.business_id = a.business_id AND br.is_active = true
    WHERE a.auth_user_id = v_uid AND a.role = 'ADMIN' AND a.is_active = true
      AND a.removed_at IS NULL AND a.staff_identity_id IS NULL
      AND a.business_id = p_business_id AND a.branch_id = p_branch_id
  ) THEN RAISE EXCEPTION 'Active Admin authority required for this branch' USING ERRCODE = '42501'; END IF;
  IF p_business_name IS NOT NULL AND length(btrim(p_business_name)) NOT BETWEEN 1 AND 200 THEN
    RAISE EXCEPTION 'Invalid business name' USING ERRCODE = '22023';
  END IF;
  IF p_address IS NOT NULL AND length(p_address) > 500 THEN RAISE EXCEPTION 'Address too long' USING ERRCODE = '22023'; END IF;
  IF p_phone IS NOT NULL AND length(p_phone) > 50 THEN RAISE EXCEPTION 'Phone too long' USING ERRCODE = '22023'; END IF;
  IF p_tax_registration_number IS NOT NULL AND length(p_tax_registration_number) > 100 THEN
    RAISE EXCEPTION 'Tax registration number too long' USING ERRCODE = '22023';
  END IF;

  IF p_business_name IS NOT NULL THEN
    UPDATE public.businesses b SET name = btrim(p_business_name), updated_at = pg_catalog.now()
    WHERE b.id = p_business_id AND b.is_active = true;
  END IF;
  UPDATE public.branches br
  SET address = NULLIF(btrim(COALESCE(p_address, '')), ''),
      phone = NULLIF(btrim(COALESCE(p_phone, '')), ''),
      tax_registration_number = NULLIF(btrim(COALESCE(p_tax_registration_number, '')), ''),
      updated_at = pg_catalog.now()
  WHERE br.id = p_branch_id AND br.business_id = p_business_id AND br.is_active = true
  RETURNING br.updated_at INTO v_updated;
  IF v_updated IS NULL THEN RAISE EXCEPTION 'Active branch not found' USING ERRCODE = 'P0002'; END IF;
  RETURN QUERY SELECT p_business_id, p_branch_id, v_updated;
END;
$function$;

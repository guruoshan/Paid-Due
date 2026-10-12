-- Permission edits must not invalidate the cashier's transport session.
-- Authorization is checked against the live membership on every financial ingest.
-- The Android client can then refresh metadata with the same session token.
CREATE OR REPLACE FUNCTION public.update_admin_cashier_permissions(
  p_membership_id text,
  p_permissions jsonb
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_actor uuid := auth.uid();
  v_target public.business_memberships%ROWTYPE;
  v_before jsonb;
  v_after jsonb;
BEGIN
  IF v_actor IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;
  IF p_membership_id IS NULL OR btrim(p_membership_id) = ''
     OR p_permissions IS NULL
     OR pg_catalog.jsonb_typeof(p_permissions) <> 'object' THEN
    RAISE EXCEPTION 'Invalid permission update' USING ERRCODE = '22023';
  END IF;

  IF EXISTS (
    SELECT 1 FROM pg_catalog.jsonb_object_keys(p_permissions) AS k(key)
    WHERE k.key NOT IN (
      'CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE',
      'USE_COUPON','ISSUE_INVOICE','ISSUE_RECEIPT','REFUND','VOID',
      'CORRECT_AMOUNT','CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY',
      'MANAGE_PRODUCTS','VIEW_REPORTS'
    )
  ) OR EXISTS (
    SELECT 1 FROM pg_catalog.jsonb_each(p_permissions) AS e(key, value)
    WHERE pg_catalog.jsonb_typeof(e.value) <> 'boolean'
  ) OR (
    SELECT count(*) FROM pg_catalog.jsonb_object_keys(p_permissions)
  ) <> 14 THEN
    RAISE EXCEPTION 'Invalid staff permissions' USING ERRCODE = '22023';
  END IF;

  SELECT cashier.* INTO v_target
  FROM public.business_memberships cashier
  WHERE cashier.id = p_membership_id
    AND cashier.role = 'CASHIER'
    AND cashier.staff_identity_id IS NOT NULL
    AND cashier.removed_at IS NULL
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Cashier not found' USING ERRCODE = 'P0002';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.business_memberships admin
    JOIN public.profiles profile
      ON profile.auth_user_id = admin.auth_user_id AND profile.is_active = true
    JOIN public.businesses business
      ON business.id = admin.business_id AND business.is_active = true
    JOIN public.branches branch
      ON branch.id = admin.branch_id
     AND branch.business_id = admin.business_id AND branch.is_active = true
    WHERE admin.auth_user_id = v_actor
      AND admin.business_id = v_target.business_id
      AND admin.branch_id = v_target.branch_id
      AND admin.role = 'ADMIN'
      AND admin.is_active = true
      AND admin.removed_at IS NULL
      AND admin.staff_identity_id IS NULL
  ) THEN
    RAISE EXCEPTION 'Admin authority required for this cashier' USING ERRCODE = '42501';
  END IF;

  v_before := pg_catalog.jsonb_build_object(
    'CREATE_SALE',v_target.can_create_sale,
    'ACCEPT_CASH',v_target.can_accept_cash,
    'ACCEPT_ONLINE',v_target.can_accept_online,
    'CREATE_CREDIT_SALE',v_target.can_create_credit_sale,
    'USE_COUPON',v_target.can_use_coupon,
    'ISSUE_INVOICE',v_target.can_issue_invoice,
    'ISSUE_RECEIPT',v_target.can_issue_receipt,
    'REFUND',v_target.can_refund,
    'VOID',v_target.can_void,
    'CORRECT_AMOUNT',v_target.can_correct_amount,
    'CHANGE_PAYMENT_METHOD',v_target.can_change_payment_method,
    'VIEW_BROAD_HISTORY',v_target.can_view_broad_history,
    'MANAGE_PRODUCTS',v_target.can_manage_products,
    'VIEW_REPORTS',v_target.can_view_reports
  );
  v_after := p_permissions;

  UPDATE public.business_memberships
  SET can_create_sale = (p_permissions->>'CREATE_SALE')::boolean,
      can_accept_cash = (p_permissions->>'ACCEPT_CASH')::boolean,
      can_accept_online = (p_permissions->>'ACCEPT_ONLINE')::boolean,
      can_create_credit_sale = (p_permissions->>'CREATE_CREDIT_SALE')::boolean,
      can_use_coupon = (p_permissions->>'USE_COUPON')::boolean,
      can_issue_invoice = (p_permissions->>'ISSUE_INVOICE')::boolean,
      can_issue_receipt = (p_permissions->>'ISSUE_RECEIPT')::boolean,
      can_refund = (p_permissions->>'REFUND')::boolean,
      can_void = (p_permissions->>'VOID')::boolean,
      can_correct_amount = (p_permissions->>'CORRECT_AMOUNT')::boolean,
      can_change_payment_method = (p_permissions->>'CHANGE_PAYMENT_METHOD')::boolean,
      can_view_broad_history = (p_permissions->>'VIEW_BROAD_HISTORY')::boolean,
      can_manage_products = (p_permissions->>'MANAGE_PRODUCTS')::boolean,
      can_view_reports = (p_permissions->>'VIEW_REPORTS')::boolean,
      updated_at = pg_catalog.now()
  WHERE id = v_target.id;

  INSERT INTO private.staff_admin_audit_events (
    actor_auth_user_id, business_id, branch_id, membership_id,
    event_type, before_state, after_state, occurred_at
  ) VALUES (
    v_actor, v_target.business_id, v_target.branch_id, v_target.id,
    'PERMISSIONS_UPDATED', v_before, v_after, pg_catalog.now()
  );
END;
$function$;

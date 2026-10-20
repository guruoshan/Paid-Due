-- TASK 3C — proposed only; NOT APPLIED.
-- Keep the exact deployed signature/result and partial-update semantics.
-- Rollback note: restore the pre-change function body in ROLLBACK_NOTES.md.
CREATE OR REPLACE FUNCTION public.update_cashier_permissions(
  p_membership_id text,
  p_permissions jsonb
)
RETURNS TABLE(membership_id text, updated_at timestamptz)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_target public.business_memberships%ROWTYPE;
  v_before jsonb;
  v_after jsonb;
  v_updated timestamptz;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;
  IF p_membership_id IS NULL OR btrim(p_membership_id) = ''
     OR p_permissions IS NULL OR jsonb_typeof(p_permissions) <> 'object' THEN
    RAISE EXCEPTION 'Invalid permissions' USING ERRCODE = '22023';
  END IF;
  IF EXISTS (
    SELECT 1 FROM jsonb_object_keys(p_permissions) AS k(key)
    WHERE k.key NOT IN (
      'CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE','USE_COUPON',
      'ISSUE_INVOICE','ISSUE_RECEIPT','REFUND','VOID','CORRECT_AMOUNT',
      'CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY','MANAGE_PRODUCTS','VIEW_REPORTS'
    )
  ) OR EXISTS (
    SELECT 1 FROM jsonb_each(p_permissions) AS e(key,value)
    WHERE jsonb_typeof(e.value) <> 'boolean'
  ) THEN
    RAISE EXCEPTION 'Invalid permissions' USING ERRCODE = '22023';
  END IF;

  SELECT m.* INTO v_target
  FROM public.business_memberships m
  WHERE m.id = p_membership_id
    AND m.role = 'CASHIER'
    AND m.staff_identity_id IS NOT NULL
    AND m.removed_at IS NULL
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Cashier membership not found' USING ERRCODE = 'P0002';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.business_memberships a
    JOIN public.profiles p ON p.auth_user_id = a.auth_user_id AND p.is_active
    JOIN public.businesses b ON b.id = a.business_id AND b.is_active
    JOIN public.branches br ON br.id = a.branch_id AND br.business_id = a.business_id AND br.is_active
    WHERE a.auth_user_id = v_uid
      AND a.role = 'ADMIN'
      AND a.is_active
      AND a.removed_at IS NULL
      AND a.staff_identity_id IS NULL
      AND a.business_id = v_target.business_id
      AND a.branch_id = v_target.branch_id
  ) THEN
    RAISE EXCEPTION 'Admin authority required' USING ERRCODE = '42501';
  END IF;

  v_before := jsonb_build_object(
    'CREATE_SALE',v_target.can_create_sale,'ACCEPT_CASH',v_target.can_accept_cash,
    'ACCEPT_ONLINE',v_target.can_accept_online,'CREATE_CREDIT_SALE',v_target.can_create_credit_sale,
    'USE_COUPON',v_target.can_use_coupon,'ISSUE_INVOICE',v_target.can_issue_invoice,
    'ISSUE_RECEIPT',v_target.can_issue_receipt,'REFUND',v_target.can_refund,'VOID',v_target.can_void,
    'CORRECT_AMOUNT',v_target.can_correct_amount,'CHANGE_PAYMENT_METHOD',v_target.can_change_payment_method,
    'VIEW_BROAD_HISTORY',v_target.can_view_broad_history,'MANAGE_PRODUCTS',v_target.can_manage_products,
    'VIEW_REPORTS',v_target.can_view_reports
  );

  UPDATE public.business_memberships m SET
    can_create_sale = COALESCE((p_permissions ->> 'CREATE_SALE')::boolean, m.can_create_sale),
    can_accept_cash = COALESCE((p_permissions ->> 'ACCEPT_CASH')::boolean, m.can_accept_cash),
    can_accept_online = COALESCE((p_permissions ->> 'ACCEPT_ONLINE')::boolean, m.can_accept_online),
    can_create_credit_sale = COALESCE((p_permissions ->> 'CREATE_CREDIT_SALE')::boolean, m.can_create_credit_sale),
    can_use_coupon = COALESCE((p_permissions ->> 'USE_COUPON')::boolean, m.can_use_coupon),
    can_issue_invoice = COALESCE((p_permissions ->> 'ISSUE_INVOICE')::boolean, m.can_issue_invoice),
    can_issue_receipt = COALESCE((p_permissions ->> 'ISSUE_RECEIPT')::boolean, m.can_issue_receipt),
    can_refund = COALESCE((p_permissions ->> 'REFUND')::boolean, m.can_refund),
    can_void = COALESCE((p_permissions ->> 'VOID')::boolean, m.can_void),
    can_correct_amount = COALESCE((p_permissions ->> 'CORRECT_AMOUNT')::boolean, m.can_correct_amount),
    can_change_payment_method = COALESCE((p_permissions ->> 'CHANGE_PAYMENT_METHOD')::boolean, m.can_change_payment_method),
    can_view_broad_history = COALESCE((p_permissions ->> 'VIEW_BROAD_HISTORY')::boolean, m.can_view_broad_history),
    can_manage_products = COALESCE((p_permissions ->> 'MANAGE_PRODUCTS')::boolean, m.can_manage_products),
    can_view_reports = COALESCE((p_permissions ->> 'VIEW_REPORTS')::boolean, m.can_view_reports),
    updated_at = now()
  WHERE m.id = p_membership_id
  RETURNING m.* INTO v_target;

  v_after := jsonb_build_object(
    'CREATE_SALE',v_target.can_create_sale,'ACCEPT_CASH',v_target.can_accept_cash,
    'ACCEPT_ONLINE',v_target.can_accept_online,'CREATE_CREDIT_SALE',v_target.can_create_credit_sale,
    'USE_COUPON',v_target.can_use_coupon,'ISSUE_INVOICE',v_target.can_issue_invoice,
    'ISSUE_RECEIPT',v_target.can_issue_receipt,'REFUND',v_target.can_refund,'VOID',v_target.can_void,
    'CORRECT_AMOUNT',v_target.can_correct_amount,'CHANGE_PAYMENT_METHOD',v_target.can_change_payment_method,
    'VIEW_BROAD_HISTORY',v_target.can_view_broad_history,'MANAGE_PRODUCTS',v_target.can_manage_products,
    'VIEW_REPORTS',v_target.can_view_reports
  );

  UPDATE private.cashier_sync_sessions
  SET revoked_at = COALESCE(revoked_at, now())
  WHERE membership_id = v_target.id AND revoked_at IS NULL;

  INSERT INTO private.staff_admin_audit_events (
    actor_auth_user_id,business_id,branch_id,membership_id,event_type,before_state,after_state,occurred_at
  ) VALUES (
    v_uid,v_target.business_id,v_target.branch_id,v_target.id,'PERMISSIONS_UPDATED',v_before,v_after,now()
  );

  RETURN QUERY SELECT v_target.id, v_target.updated_at;
END;
$function$;

-- Preserve the existing authenticated grant; do not revoke it.
GRANT EXECUTE ON FUNCTION public.update_cashier_permissions(text,jsonb) TO authenticated;

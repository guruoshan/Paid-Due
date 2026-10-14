-- Expand cashier permission requests to the full 14-key API contract.
-- This is a narrow authorization-schema change: no existing requests or audit rows are rewritten.
ALTER TABLE private.cashier_permission_requests
  DROP CONSTRAINT IF EXISTS cashier_permission_requests_permission_key_check;
ALTER TABLE private.cashier_permission_requests
  ADD CONSTRAINT cashier_permission_requests_permission_key_check
  CHECK (permission_key IN (
    'CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE',
    'USE_COUPON','ISSUE_INVOICE','ISSUE_RECEIPT','REFUND','VOID',
    'CORRECT_AMOUNT','CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY',
    'MANAGE_PRODUCTS','VIEW_REPORTS'
  ));

CREATE OR REPLACE FUNCTION public.create_cashier_permission_request(
  p_membership_id text, p_permission_key text, p_note text DEFAULT NULL
) RETURNS TABLE(request_id uuid, created_at timestamptz, duplicate boolean)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_member public.business_memberships%ROWTYPE;
  v_allowed boolean;
  v_id uuid;
  v_created timestamptz;
BEGIN
  IF p_membership_id IS NULL OR btrim(p_membership_id) = '' OR p_permission_key IS NULL OR
     p_permission_key NOT IN ('CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE',
       'USE_COUPON','ISSUE_INVOICE','REFUND','VOID','CORRECT_AMOUNT',
       'CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY','ISSUE_RECEIPT','MANAGE_PRODUCTS','VIEW_REPORTS') OR
     (p_note IS NOT NULL AND length(p_note) > 500) THEN
    RAISE EXCEPTION 'Invalid permission request' USING ERRCODE = '22023';
  END IF;
  SELECT m.* INTO v_member
  FROM public.business_memberships m
  JOIN private.staff_identities si ON si.id = m.staff_identity_id AND si.is_active = true
  JOIN public.businesses b ON b.id = m.business_id AND b.is_active = true
  JOIN public.branches br ON br.id = m.branch_id AND br.business_id = m.business_id AND br.is_active = true
  WHERE m.id = p_membership_id AND m.role = 'CASHIER' AND m.is_active = true AND m.removed_at IS NULL;
  IF NOT FOUND THEN RAISE EXCEPTION 'Active cashier membership required' USING ERRCODE = '42501'; END IF;
  v_allowed := CASE p_permission_key
    WHEN 'CREATE_SALE' THEN v_member.can_create_sale
    WHEN 'ACCEPT_CASH' THEN v_member.can_accept_cash
    WHEN 'ACCEPT_ONLINE' THEN v_member.can_accept_online
    WHEN 'CREATE_CREDIT_SALE' THEN v_member.can_create_credit_sale
    WHEN 'USE_COUPON' THEN v_member.can_use_coupon
    WHEN 'ISSUE_INVOICE' THEN v_member.can_issue_invoice
    WHEN 'ISSUE_RECEIPT' THEN v_member.can_issue_receipt
    WHEN 'REFUND' THEN v_member.can_refund
    WHEN 'VOID' THEN v_member.can_void
    WHEN 'CORRECT_AMOUNT' THEN v_member.can_correct_amount
    WHEN 'CHANGE_PAYMENT_METHOD' THEN v_member.can_change_payment_method
    WHEN 'VIEW_BROAD_HISTORY' THEN v_member.can_view_broad_history
    WHEN 'MANAGE_PRODUCTS' THEN v_member.can_manage_products
    WHEN 'VIEW_REPORTS' THEN v_member.can_view_reports

    ELSE false END;
  IF COALESCE(v_allowed, false) THEN RAISE EXCEPTION 'Permission is already granted' USING ERRCODE = '22023'; END IF;

  SELECT r.id, r.created_at INTO v_id, v_created FROM private.cashier_permission_requests r
    WHERE r.requester_membership_id = v_member.id AND r.permission_key = p_permission_key AND r.status = 'PENDING';
  IF v_id IS NOT NULL THEN RETURN QUERY SELECT v_id, v_created, true; RETURN; END IF;

  INSERT INTO private.cashier_permission_requests(business_id,branch_id,requester_membership_id,permission_key,note)
    VALUES(v_member.business_id,v_member.branch_id,v_member.id,p_permission_key,NULLIF(btrim(p_note),'' ))
    RETURNING id, private.cashier_permission_requests.created_at INTO v_id,v_created;

  INSERT INTO private.business_notifications(event_key,business_id,branch_id,recipient_membership_id,notification_type,title,body,payload)
  SELECT 'PERMISSION_REQUEST:' || v_id::text || ':' || admin.id, v_member.business_id, v_member.branch_id, admin.id,
    'PERMISSION_REQUEST','Cashier requested access',
    COALESCE(si.display_name,'A cashier') || ' requested ' || p_permission_key || ' permission.',
    jsonb_build_object('request_id',v_id,'requester_membership_id',v_member.id,'requester_name',COALESCE(si.display_name,'Cashier'),
      'permission_key',p_permission_key,'note',NULLIF(btrim(p_note),'' ))
  FROM public.business_memberships admin
  JOIN public.profiles profile ON profile.auth_user_id = admin.auth_user_id AND profile.is_active = true
  LEFT JOIN private.staff_identities si ON si.id = v_member.staff_identity_id
  WHERE admin.business_id = v_member.business_id AND admin.branch_id = v_member.branch_id
    AND admin.role = 'ADMIN' AND admin.is_active = true AND admin.removed_at IS NULL AND admin.staff_identity_id IS NULL
  ON CONFLICT(event_key) DO NOTHING;

  RETURN QUERY SELECT v_id,v_created,false;
END;
$function$;

CREATE OR REPLACE FUNCTION public.resolve_admin_permission_request(
  p_request_id uuid,
  p_decision text,
  p_additional_permissions jsonb DEFAULT '{}'::jsonb
) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_actor uuid := auth.uid();
  v_req private.cashier_permission_requests%ROWTYPE;
  v_target public.business_memberships%ROWTYPE;
  v_admin public.business_memberships%ROWTYPE;
  v_before jsonb;
  v_after jsonb;
  v_key text;
BEGIN
  IF v_actor IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501'; END IF;
  p_additional_permissions := COALESCE(p_additional_permissions, '{}'::jsonb);
  IF p_decision IS NULL OR p_decision NOT IN ('APPROVE','REJECT')
     OR jsonb_typeof(p_additional_permissions) IS DISTINCT FROM 'object' THEN
    RAISE EXCEPTION 'Invalid permission request decision' USING ERRCODE = '22023';
  END IF;
  IF EXISTS (
    SELECT 1 FROM jsonb_each(p_additional_permissions) e
    WHERE e.key NOT IN ('CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE',
      'USE_COUPON','ISSUE_INVOICE','REFUND','VOID','CORRECT_AMOUNT',
      'CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY','ISSUE_RECEIPT','MANAGE_PRODUCTS','VIEW_REPORTS')
      OR jsonb_typeof(e.value) <> 'boolean' OR e.value <> 'true'::jsonb
  ) THEN RAISE EXCEPTION 'Additional permissions must be known permissions set to true' USING ERRCODE = '22023'; END IF;

  SELECT * INTO v_req FROM private.cashier_permission_requests WHERE id = p_request_id FOR UPDATE;
  IF NOT FOUND OR v_req.status <> 'PENDING' THEN RAISE EXCEPTION 'Permission request not pending' USING ERRCODE = 'P0002'; END IF;
  SELECT m.* INTO v_admin FROM public.business_memberships m
  JOIN public.profiles profile ON profile.auth_user_id = m.auth_user_id AND profile.is_active = true
  JOIN public.businesses business ON business.id = m.business_id AND business.is_active = true
  JOIN public.branches branch ON branch.id = m.branch_id AND branch.business_id = m.business_id AND branch.is_active = true
  WHERE m.auth_user_id = v_actor AND m.business_id = v_req.business_id AND m.branch_id = v_req.branch_id
    AND m.role = 'ADMIN' AND m.is_active = true AND m.removed_at IS NULL AND m.staff_identity_id IS NULL;
  IF NOT FOUND THEN RAISE EXCEPTION 'Admin authority required' USING ERRCODE = '42501'; END IF;
  SELECT m.* INTO v_target
  FROM public.business_memberships m
  JOIN private.staff_identities si ON si.id = m.staff_identity_id AND si.is_active = true
  WHERE m.id = v_req.requester_membership_id
  FOR UPDATE OF m;
  IF NOT FOUND OR v_target.role <> 'CASHIER' OR NOT v_target.is_active OR v_target.removed_at IS NOT NULL
     OR v_target.business_id <> v_req.business_id OR v_target.branch_id <> v_req.branch_id
     OR v_target.staff_identity_id IS NULL THEN
    RAISE EXCEPTION 'Cashier is no longer active' USING ERRCODE = 'P0002';
  END IF;

  IF p_decision = 'APPROVE' THEN
    v_before := jsonb_build_object(
      'CREATE_SALE',v_target.can_create_sale,'ACCEPT_CASH',v_target.can_accept_cash,
      'ACCEPT_ONLINE',v_target.can_accept_online,'CREATE_CREDIT_SALE',v_target.can_create_credit_sale,
      'USE_COUPON',v_target.can_use_coupon,'ISSUE_INVOICE',v_target.can_issue_invoice,
      'ISSUE_RECEIPT',v_target.can_issue_receipt,'REFUND',v_target.can_refund,'VOID',v_target.can_void,
      'CORRECT_AMOUNT',v_target.can_correct_amount,'CHANGE_PAYMENT_METHOD',v_target.can_change_payment_method,
      'VIEW_BROAD_HISTORY',v_target.can_view_broad_history,'MANAGE_PRODUCTS',v_target.can_manage_products,
      'VIEW_REPORTS',v_target.can_view_reports
    );
    v_after := v_before || jsonb_build_object(v_req.permission_key, true) || p_additional_permissions;
    PERFORM set_config('app.skip_permissions_notification', 'true', true);
    UPDATE public.business_memberships SET
      can_create_sale = COALESCE((v_after->>'CREATE_SALE')::boolean, can_create_sale),
      can_accept_cash = COALESCE((v_after->>'ACCEPT_CASH')::boolean, can_accept_cash),
      can_accept_online = COALESCE((v_after->>'ACCEPT_ONLINE')::boolean, can_accept_online),
      can_create_credit_sale = COALESCE((v_after->>'CREATE_CREDIT_SALE')::boolean, can_create_credit_sale),
      can_use_coupon = COALESCE((v_after->>'USE_COUPON')::boolean, can_use_coupon),
      can_issue_invoice = COALESCE((v_after->>'ISSUE_INVOICE')::boolean, can_issue_invoice),
      can_issue_receipt = COALESCE((v_after->>'ISSUE_RECEIPT')::boolean, can_issue_receipt),
      can_refund = COALESCE((v_after->>'REFUND')::boolean, can_refund),
      can_void = COALESCE((v_after->>'VOID')::boolean, can_void),
      can_correct_amount = COALESCE((v_after->>'CORRECT_AMOUNT')::boolean, can_correct_amount),
      can_change_payment_method = COALESCE((v_after->>'CHANGE_PAYMENT_METHOD')::boolean, can_change_payment_method),
      can_view_broad_history = COALESCE((v_after->>'VIEW_BROAD_HISTORY')::boolean, can_view_broad_history),
      can_manage_products = COALESCE((v_after->>'MANAGE_PRODUCTS')::boolean, can_manage_products),
      can_view_reports = COALESCE((v_after->>'VIEW_REPORTS')::boolean, can_view_reports),

      updated_at = now()
    WHERE id = v_target.id;
    INSERT INTO private.staff_admin_audit_events(
      actor_auth_user_id,business_id,branch_id,membership_id,event_type,before_state,after_state,occurred_at
    ) VALUES(v_actor,v_req.business_id,v_req.branch_id,v_target.id,'PERMISSIONS_UPDATED',v_before,v_after,now());
  END IF;

  UPDATE private.cashier_permission_requests SET
    status = CASE WHEN p_decision = 'APPROVE' THEN 'APPROVED' ELSE 'REJECTED' END,
    resolved_at = now(), resolved_by_auth_user_id = v_actor,
    granted_permissions = CASE WHEN p_decision = 'APPROVE' THEN jsonb_build_object(v_req.permission_key, true) || p_additional_permissions ELSE '{}'::jsonb END
  WHERE id = v_req.id;

  PERFORM private.enqueue_business_notification(
    'PERMISSION_REQUEST_RESULT:' || v_req.id::text,
    v_req.business_id, v_req.branch_id, v_req.requester_membership_id,
    'PERMISSION_REQUEST_RESULT',
    CASE WHEN p_decision = 'APPROVE' THEN 'Permission request approved' ELSE 'Permission request declined' END,
    CASE WHEN p_decision = 'APPROVE'
      THEN 'Your request for ' || v_req.permission_key || ' was approved. Reopen or refresh the app to load your updated permissions.'
      ELSE 'Your request for ' || v_req.permission_key || ' was declined. Your existing permissions are unchanged.'
    END,
    jsonb_build_object('request_id', v_req.id, 'decision', p_decision,
      'requested_permission', v_req.permission_key,
      'additional_permissions', CASE WHEN p_decision = 'APPROVE' THEN p_additional_permissions ELSE '{}'::jsonb END)
  );
END;
$function$;

DO $verify$
DECLARE
  v_old text := '''CHANGE_PAYMENT_METHOD'',''VIEW_BROAD_HISTORY'')';
  v_create text;
  v_resolve text;
  v_constraint text;
BEGIN
  SELECT pg_get_constraintdef(oid) INTO v_constraint
  FROM pg_constraint
  WHERE conrelid = 'private.cashier_permission_requests'::regclass
    AND conname = 'cashier_permission_requests_permission_key_check';
  IF v_constraint IS NULL
     OR position('ISSUE_RECEIPT' IN v_constraint) = 0
     OR position('MANAGE_PRODUCTS' IN v_constraint) = 0
     OR position('VIEW_REPORTS' IN v_constraint) = 0 THEN
    RAISE EXCEPTION 'Permission migration verification failed: constraint is incomplete';
  END IF;
  v_create := pg_get_functiondef('public.create_cashier_permission_request(text,text,text)'::regprocedure);
  v_resolve := pg_get_functiondef('public.resolve_admin_permission_request(uuid,text,jsonb)'::regprocedure);
  IF position(v_old IN v_create) > 0 OR position(v_old IN v_resolve) > 0 THEN
    RAISE EXCEPTION 'Permission migration verification failed: stale 11-key allowlist remains';
  END IF;
  IF position('ISSUE_RECEIPT' IN v_create) = 0
     OR position('MANAGE_PRODUCTS' IN v_create) = 0
     OR position('VIEW_REPORTS' IN v_create) = 0
     OR position('ISSUE_RECEIPT' IN v_resolve) = 0
     OR position('MANAGE_PRODUCTS' IN v_resolve) = 0
     OR position('VIEW_REPORTS' IN v_resolve) = 0 THEN
    RAISE EXCEPTION 'Permission migration verification failed: function allowlists are incomplete';
  END IF;
END;
$verify$;

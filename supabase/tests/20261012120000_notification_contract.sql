-- Disposable-only behavioral checks for notification migration and financial contract.
BEGIN;
DO $test$
DECLARE
  v_admin_id uuid := '22222222-2222-4222-8222-222222222222';
  v_staff_id uuid := '33333333-3333-4333-8333-333333333333';
  v_payload jsonb;
  v_text text;
  v_hash text;
  v_request_id uuid;
  v_count integer;
BEGIN
  IF to_regclass('private.business_notifications') IS NULL OR to_regclass('private.cashier_permission_requests') IS NULL THEN
    RAISE EXCEPTION 'FAIL: notification tables missing';
  END IF;
  IF to_regprocedure('public.list_admin_notifications()') IS NULL OR to_regprocedure('public.resolve_admin_permission_request(uuid,text,jsonb)') IS NULL OR to_regprocedure('public.create_cashier_permission_request(text,text,text)') IS NULL THEN
    RAISE EXCEPTION 'FAIL: notification/permission RPC missing';
  END IF;
  IF position('NOT v_membership.can_accept_cash' IN pg_get_functiondef(
       'public.ingest_cashier_financial_envelope(text,text,text,text,text,text,integer,timestamp with time zone,text,date,text,text,jsonb)'::regprocedure
     )) = 0 THEN RAISE EXCEPTION 'FAIL: financial tender guards overwritten'; END IF;

  -- resolve_admin_permission_request stores the authenticated actor on the
  -- request, so seed the corresponding managed auth row in this disposable DB.
  INSERT INTO auth.users (id, aud, role, email, encrypted_password)
  VALUES (v_admin_id, 'authenticated', 'authenticated', 'notification-test-admin@example.invalid', '')
  ON CONFLICT (id) DO NOTHING;

  INSERT INTO public.profiles (auth_user_id, display_name, is_active)
  VALUES (v_admin_id, 'Notification Test Admin', true) ON CONFLICT (auth_user_id) DO NOTHING;
  INSERT INTO public.businesses (id, name, is_active)
  VALUES ('notification-test-business', 'Notification Test Business', true) ON CONFLICT (id) DO NOTHING;
  INSERT INTO public.branches (id,business_id,name,code,country_code,currency_code,currency_decimal_places,currency_symbol,timezone_id,is_active)
  VALUES ('notification-test-branch','notification-test-business','Test Branch','NT','OM','OMR',3,'OMR','Asia/Muscat',true)
  ON CONFLICT (id) DO NOTHING;
  INSERT INTO private.staff_identities (id,canonical_phone,display_name,credential_hash,credential_salt,is_active)
  VALUES (v_staff_id,'+96890000001','Notification Test Cashier','pbkdf2-sha256:600000:'||repeat('a',64),repeat('b',32),true)
  ON CONFLICT (id) DO NOTHING;
  INSERT INTO public.business_memberships (id,business_id,branch_id,auth_user_id,local_user_id,role,is_active)
  VALUES ('notification-test-admin-membership','notification-test-business','notification-test-branch',v_admin_id,'notification-test-admin','ADMIN',true)
  ON CONFLICT (id) DO NOTHING;
  INSERT INTO public.business_memberships (
    id,business_id,branch_id,staff_identity_id,local_user_id,role,is_active,
    can_create_sale,can_accept_cash,can_accept_online,can_create_credit_sale,can_use_coupon,
    can_issue_invoice,can_issue_receipt,can_refund,can_void,can_correct_amount,
    can_change_payment_method,can_view_broad_history,can_manage_products,can_view_reports
  ) VALUES (
    'notification-test-cashier-membership','notification-test-business','notification-test-branch',
    v_staff_id,'notification-test-cashier','CASHIER',true,
    true,true,true,false,false,true,true,false,false,false,false,false,false,false
  ) ON CONFLICT (id) DO NOTHING;

  v_payload := jsonb_build_object('operation_id','notification-test-close-operation','business_id','notification-test-business',
    'day_closing_report',jsonb_build_object('total_sales_minor',2500,'cash_minor',1000,'online_minor',500,'credit_minor',500,'coupon_minor',500,'difference_minor','500'));
  v_text := v_payload::text;
  v_hash := encode(extensions.digest(convert_to(v_text,'UTF8'),'sha256'),'hex');
  INSERT INTO private.financial_operation_envelopes (
    business_id,operation_id,branch_id,actor_membership_id,device_id,operation_type,schema_version,
    occurred_at,branch_timezone_id,business_local_date,canonical_text,canonical_sha256,canonical_payload
  ) VALUES ('notification-test-business','notification-test-close-operation','notification-test-branch',
    'notification-test-cashier-membership','test-device','CLOSE_CASH_SHIFT',1,now(),'Asia/Muscat',current_date,v_text,v_hash,v_payload);
  SELECT count(*) INTO v_count FROM private.business_notifications WHERE recipient_membership_id='notification-test-admin-membership'
    AND notification_type='DAY_CLOSED' AND payload->>'operation_id'='notification-test-close-operation';
  IF v_count <> 1 THEN RAISE EXCEPTION 'FAIL: day-close notification count %',v_count; END IF;
  SELECT count(*) INTO v_count FROM private.business_notifications WHERE recipient_membership_id='notification-test-admin-membership'
    AND notification_type='CASH_DISCREPANCY' AND payload->>'operation_id'='notification-test-close-operation';
  IF v_count <> 1 THEN RAISE EXCEPTION 'FAIL: cash discrepancy notification count %',v_count; END IF;

  v_payload := jsonb_build_object('operation_id','notification-test-close-large-difference','business_id','notification-test-business',
    'day_closing_report',jsonb_build_object('difference_minor',repeat('9',1000)));
  v_text := v_payload::text;
  v_hash := encode(extensions.digest(convert_to(v_text,'UTF8'),'sha256'),'hex');
  INSERT INTO private.financial_operation_envelopes (
    business_id,operation_id,branch_id,actor_membership_id,device_id,operation_type,schema_version,
    occurred_at,branch_timezone_id,business_local_date,canonical_text,canonical_sha256,canonical_payload
  ) VALUES ('notification-test-business','notification-test-close-large-difference','notification-test-branch',
    'notification-test-cashier-membership','test-device','CLOSE_CASH_SHIFT',1,now(),'Asia/Muscat',current_date,v_text,v_hash,v_payload);

  UPDATE public.business_memberships SET can_accept_cash=false,updated_at=now() WHERE id='notification-test-cashier-membership';
  SELECT count(*) INTO v_count FROM private.business_notifications WHERE recipient_membership_id='notification-test-cashier-membership' AND notification_type='PERMISSIONS_CHANGED';
  IF v_count <> 1 THEN RAISE EXCEPTION 'FAIL: cashier permission-change notification count %',v_count; END IF;
  SELECT count(*) INTO v_count FROM private.business_notifications WHERE recipient_membership_id='notification-test-admin-membership'
    AND notification_type='CASHIER_PERMISSIONS_CHANGED' AND payload->>'cashier_membership_id'='notification-test-cashier-membership';
  IF v_count <> 1 THEN RAISE EXCEPTION 'FAIL: Admin permission-change notification count %',v_count; END IF;

  SELECT request_id INTO v_request_id FROM public.create_cashier_permission_request('notification-test-cashier-membership','REFUND','Test request');
  IF v_request_id IS NULL THEN RAISE EXCEPTION 'FAIL: permission request not created'; END IF;
  PERFORM set_config('request.jwt.claim.sub',v_admin_id::text,true);
  PERFORM public.resolve_admin_permission_request(v_request_id,'APPROVE',jsonb_build_object('VIEW_BROAD_HISTORY',true));
  IF NOT (SELECT can_refund AND can_view_broad_history FROM public.business_memberships WHERE id='notification-test-cashier-membership') THEN
    RAISE EXCEPTION 'FAIL: approved permissions not applied';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM private.cashier_permission_requests WHERE id=v_request_id AND status='APPROVED') THEN
    RAISE EXCEPTION 'FAIL: request state not approved';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM private.business_notifications WHERE recipient_membership_id='notification-test-cashier-membership'
    AND notification_type='PERMISSION_REQUEST_RESULT' AND payload->>'request_id'=v_request_id::text) THEN
    RAISE EXCEPTION 'FAIL: cashier decision notification missing';
  END IF;
  RAISE NOTICE 'PASS: notification schema and RPCs';
  RAISE NOTICE 'PASS: financial tender guards preserved';
  RAISE NOTICE 'PASS: day close, cash discrepancy, malformed report';
  RAISE NOTICE 'PASS: permission change/request/approval flow';
END
$test$;
ROLLBACK;

-- Disposable/staging contract checks for the final ADMIN-only role boundary.
-- All negative write attempts are transaction-scoped and rolled back; this must run only in CI/staging.
BEGIN;
-- Exercise the installed trigger function against isolated fixtures. The
-- historical tombstone is inserted before attaching the trigger so the test
-- can independently verify reactivation and permission-grant rejection.
CREATE TEMP TABLE cashier_retirement_guard_fixture (
  id text PRIMARY KEY,
  role text NOT NULL,
  auth_user_id uuid,
  staff_identity_id uuid,
  is_active boolean NOT NULL,
  removed_at timestamptz,
  can_create_sale boolean NOT NULL,
  can_accept_cash boolean NOT NULL,
  can_accept_online boolean NOT NULL,
  can_create_credit_sale boolean NOT NULL,
  can_use_coupon boolean NOT NULL,
  can_issue_invoice boolean NOT NULL,
  can_issue_receipt boolean NOT NULL,
  can_refund boolean NOT NULL,
  can_void boolean NOT NULL,
  can_correct_amount boolean NOT NULL,
  can_change_payment_method boolean NOT NULL,
  can_view_broad_history boolean NOT NULL,
  can_manage_products boolean NOT NULL,
  can_view_reports boolean NOT NULL,
  can_create_expense boolean NOT NULL,
  can_view_customer_directory boolean NOT NULL
 ) ON COMMIT DROP;
ALTER TABLE cashier_retirement_guard_fixture
  ADD CONSTRAINT cashier_retirement_fixture_check CHECK (
    role <> 'CASHIER'
    OR (
      is_active = false AND removed_at IS NOT NULL
      AND auth_user_id IS NULL AND staff_identity_id IS NULL
      AND can_create_sale = false AND can_accept_cash = false
      AND can_accept_online = false AND can_create_credit_sale = false
      AND can_use_coupon = false AND can_issue_invoice = false
      AND can_issue_receipt = false AND can_refund = false AND can_void = false
      AND can_correct_amount = false AND can_change_payment_method = false
      AND can_view_broad_history = false AND can_manage_products = false
      AND can_view_reports = false AND can_create_expense = false
      AND can_view_customer_directory = false
    )
  );

-- Constraint-only acceptance: a fully inactive, permissionless historical
-- CASHIER tombstone is valid before the INSERT trigger is attached.
INSERT INTO cashier_retirement_guard_fixture VALUES (
  'legacy-tombstone-accepted-by-check', 'CASHIER', NULL, NULL, false, now(),
  false, false, false, false, false, false, false, false, false,
  false, false, false, false, false, false, false
);

DO $constraint_cases$
DECLARE
  v_state text;
  v_constraint text;
BEGIN
  -- The CHECK independently rejects active CASHIER state with SQLSTATE 23514.
  BEGIN
    INSERT INTO cashier_retirement_guard_fixture VALUES (
      'check-active-rejected', 'CASHIER', NULL, NULL, true, now(),
      false, false, false, false, false, false, false, false, false,
      false, false, false, false, false, false, false
    );
    RAISE EXCEPTION 'FAIL: CHECK accepted active CASHIER row';
  EXCEPTION WHEN check_violation THEN
    GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_constraint = CONSTRAINT_NAME;
    IF v_state <> '23514' OR v_constraint <> 'cashier_retirement_fixture_check' THEN
      RAISE EXCEPTION 'FAIL: active CASHIER CHECK rejection had SQLSTATE %, constraint %', v_state, v_constraint;
    END IF;
  END;

  -- The CHECK independently rejects any permission grant with SQLSTATE 23514.
  BEGIN
    INSERT INTO cashier_retirement_guard_fixture VALUES (
      'check-permission-rejected', 'CASHIER', NULL, NULL, false, now(),
      true, false, false, false, false, false, false, false, false,
      false, false, false, false, false, false, false
    );
    RAISE EXCEPTION 'FAIL: CHECK accepted CASHIER permission';
  EXCEPTION WHEN check_violation THEN
    GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_constraint = CONSTRAINT_NAME;
    IF v_state <> '23514' OR v_constraint <> 'cashier_retirement_fixture_check' THEN
      RAISE EXCEPTION 'FAIL: permission CHECK rejection had SQLSTATE %, constraint %', v_state, v_constraint;
    END IF;
  END;

  -- Principal references are forbidden on CASHIER tombstones by the CHECK.
  BEGIN
    INSERT INTO cashier_retirement_guard_fixture VALUES (
      'check-auth-user-rejected', 'CASHIER', '00000000-0000-0000-0000-000000000011', NULL, false, now(),
      false, false, false, false, false, false, false, false, false,
      false, false, false, false, false, false, false
    );
    RAISE EXCEPTION 'FAIL: CHECK accepted CASHIER auth_user_id';
  EXCEPTION WHEN check_violation THEN
    GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_constraint = CONSTRAINT_NAME;
    IF v_state <> '23514' OR v_constraint <> 'cashier_retirement_fixture_check' THEN
      RAISE EXCEPTION 'FAIL: auth_user_id CHECK rejection had SQLSTATE %, constraint %', v_state, v_constraint;
    END IF;
  END;

  BEGIN
    INSERT INTO cashier_retirement_guard_fixture VALUES (
      'check-staff-id-rejected', 'CASHIER', NULL, '00000000-0000-0000-0000-000000000012', false, now(),
      false, false, false, false, false, false, false, false, false,
      false, false, false, false, false, false, false
    );
    RAISE EXCEPTION 'FAIL: CHECK accepted CASHIER staff_identity_id';
  EXCEPTION WHEN check_violation THEN
    GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_constraint = CONSTRAINT_NAME;
    IF v_state <> '23514' OR v_constraint <> 'cashier_retirement_fixture_check' THEN
      RAISE EXCEPTION 'FAIL: staff_identity_id CHECK rejection had SQLSTATE %, constraint %', v_state, v_constraint;
    END IF;
  END;
END
$constraint_cases$;

INSERT INTO cashier_retirement_guard_fixture VALUES (
  'legacy-tombstone', 'CASHIER', NULL, NULL, false, now(),
  false, false, false, false, false, false, false, false, false,
  false, false, false, false, false, false, false
);
INSERT INTO cashier_retirement_guard_fixture VALUES (
  'admin-fixture', 'ADMIN', '00000000-0000-0000-0000-000000000001', NULL, true, NULL,
  true, true, true, true, true, true, true, true, true,
  true, true, true, true, true, true, true
);

-- Assert trigger-specific rejections by exact SQLSTATE and message.
CREATE FUNCTION pg_temp.assert_cashier_trigger_rejects(p_sql text, p_message text)
RETURNS void LANGUAGE plpgsql AS $assert$
DECLARE v_state text; v_message text;
BEGIN
  BEGIN
    EXECUTE p_sql;
    RAISE EXCEPTION 'FAIL: trigger unexpectedly accepted SQL: %', p_sql;
  EXCEPTION WHEN check_violation THEN
    GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_message = MESSAGE_TEXT;
    IF v_state <> '23514' OR v_message <> p_message THEN
      RAISE EXCEPTION 'FAIL: trigger rejection expected SQLSTATE 23514/message %, got %/%', p_message, v_state, v_message;
    END IF;
  END;
END
$assert$;

CREATE TRIGGER trg_test_cashier_retirement_guard
BEFORE INSERT OR UPDATE OF role, is_active, removed_at, auth_user_id, staff_identity_id,
  can_create_sale, can_accept_cash, can_accept_online, can_create_credit_sale, can_use_coupon,
  can_issue_invoice, can_issue_receipt, can_refund, can_void, can_correct_amount,
  can_change_payment_method, can_view_broad_history, can_manage_products, can_view_reports,
  can_create_expense, can_view_customer_directory
ON cashier_retirement_guard_fixture
FOR EACH ROW EXECUTE FUNCTION private.prevent_new_cashier_memberships();
DO $test$
DECLARE
  v_active_cashiers bigint;
  v_trigger_count integer;
  v_constraint_count integer;
  v_trigger_definition text;
  v_trigger_function oid;
  v_column text;
BEGIN
  SELECT count(*) INTO v_active_cashiers
  FROM public.business_memberships
  WHERE role = 'CASHIER'
    AND (is_active IS DISTINCT FROM false OR removed_at IS NULL
      OR auth_user_id IS NOT NULL OR staff_identity_id IS NOT NULL);
  IF v_active_cashiers <> 0 THEN
    RAISE EXCEPTION 'FAIL: % active/non-tombstone CASHIER memberships remain', v_active_cashiers;
  END IF;

  SELECT count(*) INTO v_trigger_count
  FROM pg_trigger t
  JOIN pg_class c ON c.oid = t.tgrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public'
    AND c.relname = 'business_memberships'
    AND t.tgname = 'trg_prevent_new_cashier_memberships'
    AND NOT t.tgisinternal;
  IF v_trigger_count <> 1 THEN
    RAISE EXCEPTION 'FAIL: ADMIN-only membership trigger missing';
  END IF;

  SELECT pg_get_triggerdef(t.oid), t.tgfoid
  INTO v_trigger_definition, v_trigger_function
  FROM pg_trigger t
  JOIN pg_class c ON c.oid = t.tgrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public'
    AND c.relname = 'business_memberships'
    AND t.tgname = 'trg_prevent_new_cashier_memberships'
    AND NOT t.tgisinternal;

  IF v_trigger_function <> 'private.prevent_new_cashier_memberships()'::regprocedure::oid THEN
    RAISE EXCEPTION 'FAIL: membership trigger is not bound to the retirement guard function';
  END IF;

  FOREACH v_column IN ARRAY ARRAY[
    'role', 'is_active', 'removed_at', 'auth_user_id', 'staff_identity_id',
    'can_create_sale', 'can_accept_cash', 'can_accept_online', 'can_create_credit_sale',
    'can_use_coupon', 'can_issue_invoice', 'can_issue_receipt', 'can_refund', 'can_void',
    'can_correct_amount', 'can_change_payment_method', 'can_view_broad_history',
    'can_manage_products', 'can_view_reports', 'can_create_expense', 'can_view_customer_directory'
  ] LOOP
    IF position(v_column IN lower(v_trigger_definition)) = 0 THEN
      RAISE EXCEPTION 'FAIL: membership trigger does not watch column %', v_column;
    END IF;
  END LOOP;

  SELECT count(*) INTO v_constraint_count
  FROM pg_constraint c
  JOIN pg_class r ON r.oid = c.conrelid
  JOIN pg_namespace n ON n.oid = r.relnamespace
  WHERE n.nspname = 'public'
    AND r.relname = 'business_memberships'
    AND c.conname = 'business_memberships_cashier_retirement_check'
    AND c.contype = 'c'
    AND c.convalidated;
  IF v_constraint_count <> 1 THEN
    RAISE EXCEPTION 'FAIL: validated CASHIER tombstone constraint missing';
  END IF;

  -- The tombstone CHECK intentionally permits inactive historical rows. These
  -- attempts satisfy that CHECK so only the retirement trigger can reject them.
  BEGIN
    INSERT INTO public.business_memberships (
      id, business_id, branch_id, role, auth_user_id, staff_identity_id,
      can_create_sale, can_accept_cash, can_accept_online, can_create_credit_sale,
      can_use_coupon, can_issue_invoice, can_issue_receipt, can_refund, can_void,
      can_correct_amount, can_change_payment_method, can_view_broad_history,
      can_manage_products, can_view_reports, can_create_expense,
      can_view_customer_directory, is_active, removed_at
    ) VALUES (
      '__cashier_retirement_insert_test__', '__missing_business__', '__missing_branch__',
      'CASHIER', NULL, NULL,
      false, false, false, false, false, false, false, false, false,
      false, false, false, false, false, false, false, false, now()
    );
    RAISE EXCEPTION 'FAIL: new CASHIER membership insert was accepted';
  EXCEPTION WHEN check_violation THEN
    NULL;
  END;


  BEGIN
    UPDATE cashier_retirement_guard_fixture
    SET role = 'CASHIER', auth_user_id = NULL, is_active = false, removed_at = now(),
        can_create_sale = false, can_accept_cash = false, can_accept_online = false,
        can_create_credit_sale = false, can_use_coupon = false, can_issue_invoice = false,
        can_issue_receipt = false, can_refund = false, can_void = false,
        can_correct_amount = false, can_change_payment_method = false,
        can_view_broad_history = false, can_manage_products = false, can_view_reports = false,
        can_create_expense = false, can_view_customer_directory = false
    WHERE id = 'admin-fixture';
    RAISE EXCEPTION 'FAIL: ADMIN membership conversion to CASHIER was accepted';
  EXCEPTION WHEN check_violation THEN
    NULL;
  END;

  BEGIN
    UPDATE cashier_retirement_guard_fixture SET is_active = true
    WHERE id = 'legacy-tombstone';
    RAISE EXCEPTION 'FAIL: removed CASHIER membership reactivation was accepted';
  EXCEPTION WHEN check_violation THEN
    NULL;
  END;

  BEGIN
    UPDATE cashier_retirement_guard_fixture SET can_refund = true
    WHERE id = 'legacy-tombstone';
    RAISE EXCEPTION 'FAIL: cashier permission grant was accepted';
  EXCEPTION WHEN check_violation THEN
    NULL;
  END;

  -- New CASHIER INSERT is rejected even when it would satisfy the CHECK.
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$INSERT INTO cashier_retirement_guard_fixture VALUES (
      'trigger-inactive-insert', 'CASHIER', NULL, NULL, false, now(),
      false, false, false, false, false, false, false, false, false,
      false, false, false, false, false, false, false
    )$sql$,
    'CASHIER role is retired; create ADMIN memberships only'
  );
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$INSERT INTO cashier_retirement_guard_fixture VALUES (
      'trigger-active-insert', 'CASHIER', NULL, NULL, true, now(),
      false, false, false, false, false, false, false, false, false,
      false, false, false, false, false, false, false
    )$sql$,
    'CASHIER role is retired; create ADMIN memberships only'
  );
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$INSERT INTO cashier_retirement_guard_fixture VALUES (
      'trigger-permission-insert', 'CASHIER', NULL, NULL, false, now(),
      true, false, false, false, false, false, false, false, false,
      false, false, false, false, false, false, false
    )$sql$,
    'CASHIER role is retired; create ADMIN memberships only'
  );
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$INSERT INTO cashier_retirement_guard_fixture VALUES (
      'trigger-auth-user-insert', 'CASHIER', '00000000-0000-0000-0000-000000000013', NULL, false, now(),
      false, false, false, false, false, false, false, false, false,
      false, false, false, false, false, false, false
    )$sql$,
    'CASHIER role is retired; create ADMIN memberships only'
  );
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$INSERT INTO cashier_retirement_guard_fixture VALUES (
      'trigger-staff-identity-insert', 'CASHIER', NULL, '00000000-0000-0000-0000-000000000014', false, now(),
      false, false, false, false, false, false, false, false, false,
      false, false, false, false, false, false, false
    )$sql$,
    'CASHIER role is retired; create ADMIN memberships only'
  );
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$UPDATE cashier_retirement_guard_fixture SET role='CASHIER', auth_user_id=NULL,
      is_active=false, removed_at=now(), can_create_sale=false, can_accept_cash=false,
      can_accept_online=false, can_create_credit_sale=false, can_use_coupon=false,
      can_issue_invoice=false, can_issue_receipt=false, can_refund=false, can_void=false,
      can_correct_amount=false, can_change_payment_method=false, can_view_broad_history=false,
      can_manage_products=false, can_view_reports=false, can_create_expense=false,
      can_view_customer_directory=false WHERE id='admin-fixture'$sql$,
    'CASHIER role is retired; role conversion to CASHIER is prohibited'
  );
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$UPDATE cashier_retirement_guard_fixture SET is_active=true WHERE id='legacy-tombstone'$sql$,
    'Historical CASHIER tombstones cannot be activated or granted permissions'
  );
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$UPDATE cashier_retirement_guard_fixture SET can_refund=true WHERE id='legacy-tombstone'$sql$,
    'Historical CASHIER tombstones cannot be activated or granted permissions'
  );

  -- ADMIN inserts and updates carrying permissions remain accepted.
  INSERT INTO cashier_retirement_guard_fixture VALUES (
    'admin-insert-accepted', 'ADMIN', '00000000-0000-0000-0000-000000000015', NULL, true, NULL,
    true, true, true, true, true, true, true, true, true,
    true, true, true, true, true, true, true
  );
  UPDATE cashier_retirement_guard_fixture SET can_refund=true, can_void=true
  WHERE id='admin-fixture';
  IF NOT EXISTS (SELECT 1 FROM cashier_retirement_guard_fixture
    WHERE id='admin-fixture' AND can_refund AND can_void) THEN
    RAISE EXCEPTION 'FAIL: ADMIN permission update was not persisted';
  END IF;

  -- BEGIN GENERATED RETIRED FUNCTION ASSERTIONS. Regenerate with scripts/check_retired_functions.py --write.
  IF to_regprocedure('private.notify_cashier_of_permission_change()') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: private.notify_cashier_of_permission_change()';
  END IF;
  IF to_regprocedure('public.activate_staff_invite(text, text, text)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.activate_staff_invite(text, text, text)';
  END IF;
  IF to_regprocedure('public.cashier_login_credential(text)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.cashier_login_credential(text)';
  END IF;
  IF to_regprocedure('public.cashier_restoration_payload(text)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.cashier_restoration_payload(text)';
  END IF;
  IF to_regprocedure('public.create_cashier_permission_request(text, text, text)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.create_cashier_permission_request(text, text, text)';
  END IF;
  IF to_regprocedure('public.create_cashier_sync_session(text, text)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.create_cashier_sync_session(text, text)';
  END IF;
  IF to_regprocedure('public.create_staff_invite_for_admin(text, text, text, text, text, jsonb, uuid)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.create_staff_invite_for_admin(text, text, text, text, text, jsonb, uuid)';
  END IF;
  IF to_regprocedure('public.fetch_cashier_financial_envelopes(text, text, text, timestamptz, text, integer)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.fetch_cashier_financial_envelopes(text, text, text, timestamptz, text, integer)';
  END IF;
  IF to_regprocedure('public.fetch_cashier_financial_envelopes_v2(text, text, text, timestamptz, text, integer)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.fetch_cashier_financial_envelopes_v2(text, text, text, timestamptz, text, integer)';
  END IF;
  IF to_regprocedure('public.fetch_cashier_metadata(text, text, text)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.fetch_cashier_metadata(text, text, text)';
  END IF;
  IF to_regprocedure('public.fetch_cashier_restoration(text)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.fetch_cashier_restoration(text)';
  END IF;
  IF to_regprocedure('public.ingest_cashier_financial_envelope(text, text, text, text, text, text, integer, timestamptz, text, date, text, text, jsonb)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.ingest_cashier_financial_envelope(text, text, text, text, text, text, integer, timestamptz, text, date, text, text, jsonb)';
  END IF;
  IF to_regprocedure('public.list_admin_cashiers()') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.list_admin_cashiers()';
  END IF;
  IF to_regprocedure('public.list_admin_cashiers_with_permissions()') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.list_admin_cashiers_with_permissions()';
  END IF;
  IF to_regprocedure('public.list_admin_permission_requests()') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.list_admin_permission_requests()';
  END IF;
  IF to_regprocedure('public.list_cashier_notifications(text)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.list_cashier_notifications(text)';
  END IF;
  IF to_regprocedure('public.list_staff_invites(text)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.list_staff_invites(text)';
  END IF;
  IF to_regprocedure('public.mark_cashier_notification_read(text, uuid)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.mark_cashier_notification_read(text, uuid)';
  END IF;
  IF to_regprocedure('public.preview_staff_invite(text)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.preview_staff_invite(text)';
  END IF;
  IF to_regprocedure('public.remove_admin_cashier(text)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.remove_admin_cashier(text)';
  END IF;
  IF to_regprocedure('public.resolve_admin_permission_request(uuid, text, jsonb)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.resolve_admin_permission_request(uuid, text, jsonb)';
  END IF;
  IF to_regprocedure('public.revoke_staff_invite(uuid)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.revoke_staff_invite(uuid)';
  END IF;
  IF to_regprocedure('public.sync_customer_directory_list(text, text, text, timestamptz, integer)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.sync_customer_directory_list(text, text, text, timestamptz, integer)';
  END IF;
  IF to_regprocedure('public.sync_customer_directory_upsert(text, text, text, text, text, text, timestamptz)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.sync_customer_directory_upsert(text, text, text, text, text, text, timestamptz)';
  END IF;
  IF to_regprocedure('public.update_admin_cashier_permissions(text, jsonb)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.update_admin_cashier_permissions(text, jsonb)';
  END IF;
  IF to_regprocedure('public.update_cashier_permissions(text, jsonb)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.update_cashier_permissions(text, jsonb)';
  END IF;
  IF to_regprocedure('public.verify_cashier_notification_session(text)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.verify_cashier_notification_session(text)';
  END IF;
  IF to_regprocedure('public.verify_cashier_sync_session(text)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: retired function remains installed: public.verify_cashier_sync_session(text)';
  END IF;
  -- END GENERATED RETIRED FUNCTION ASSERTIONS.

  RAISE NOTICE 'PASS: no active CASHIER memberships';
  RAISE NOTICE 'PASS: new CASHIER membership insert blocked by trigger';
  RAISE NOTICE 'PASS: ADMIN-to-CASHIER role conversion blocked by trigger';
  RAISE NOTICE 'PASS: removed CASHIER membership reactivation blocked';
  RAISE NOTICE 'PASS: cashier permission grant blocked';
  RAISE NOTICE 'PASS: legacy CASHIER rows constrained to inactive permissionless tombstones';
  RAISE NOTICE 'PASS: retired cashier RPCs absent';
END
$test$;
ROLLBACK;

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

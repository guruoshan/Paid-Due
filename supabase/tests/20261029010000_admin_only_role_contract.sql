-- Disposable/staging contract checks for the final ADMIN-only role boundary.
-- Every behavioral write targets public.business_memberships itself. The CI gate
-- supplies only the minimal referenced businesses/auth.users tables and relevant
-- production CHECK constraints; all fixture rows and mutations roll back.
BEGIN;

-- Isolated tenant and authenticated principal fixtures for real-table FK checks.
INSERT INTO public.businesses (id) VALUES ('__cashier_guard_test_business__');
INSERT INTO public.branches (id, business_id) VALUES ('__cashier_guard_test_branch__', '__cashier_guard_test_business__');
INSERT INTO auth.users (id) VALUES ('00000000-0000-0000-0000-000000000001');
INSERT INTO private.staff_identities (id) VALUES
  ('00000000-0000-0000-0000-000000000011'),
  ('00000000-0000-0000-0000-000000000012'),
  ('00000000-0000-0000-0000-000000000013'),
  ('00000000-0000-0000-0000-000000000014');

CREATE FUNCTION pg_temp.set_retirement_trigger(p_enabled boolean)
RETURNS void LANGUAGE plpgsql AS $toggle$
DECLARE v_exists boolean;
BEGIN
  SELECT EXISTS (
    SELECT 1 FROM pg_trigger t
    JOIN pg_class c ON c.oid = t.tgrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND c.relname = 'business_memberships'
      AND t.tgname = 'trg_prevent_new_cashier_memberships'
      AND NOT t.tgisinternal
  ) INTO v_exists;
  -- In negative-control mode the intentionally dropped trigger is absent;
  -- do not recreate it while isolating CHECK behavior.
  IF v_exists THEN
    IF p_enabled THEN
      EXECUTE 'ALTER TABLE public.business_memberships ENABLE TRIGGER trg_prevent_new_cashier_memberships';
    ELSE
      EXECUTE 'ALTER TABLE public.business_memberships DISABLE TRIGGER trg_prevent_new_cashier_memberships';
    END IF;
  END IF;
END
$toggle$;

CREATE FUNCTION pg_temp.assert_constraint_rejects(
  p_sql text, p_constraint text, p_fail_message text
) RETURNS void LANGUAGE plpgsql AS $assert$
DECLARE v_state text; v_constraint text;
BEGIN
  BEGIN
    EXECUTE p_sql;
    RAISE EXCEPTION '%', p_fail_message;
  EXCEPTION WHEN check_violation THEN
    GET STACKED DIAGNOSTICS
      v_state = RETURNED_SQLSTATE,
      v_constraint = CONSTRAINT_NAME;
    IF v_state <> '23514' OR v_constraint <> p_constraint THEN
      RAISE EXCEPTION 'FAIL: expected SQLSTATE 23514/constraint %, got %/%',
        p_constraint, v_state, v_constraint;
    END IF;
  END;
END
$assert$;

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
      RAISE EXCEPTION 'FAIL: trigger rejection expected SQLSTATE 23514/message %, got %/%',
        p_message, v_state, v_message;
    END IF;
  END;
END
$assert$;

-- Seed a real-table legacy tombstone with the trigger temporarily disabled.
-- This proves the production CHECK accepts the historical tombstone shape;
-- the transaction is rolled back at the end of this contract.
SELECT pg_temp.set_retirement_trigger(false);
INSERT INTO public.business_memberships (
  id, business_id, branch_id, role, auth_user_id, staff_identity_id,
  is_active, removed_at, can_create_sale, can_accept_cash, can_accept_online,
  can_create_credit_sale, can_use_coupon, can_issue_invoice, can_issue_receipt,
  can_refund, can_void, can_correct_amount, can_change_payment_method,
  can_view_broad_history, can_manage_products, can_view_reports,
  can_create_expense, can_view_customer_directory
) VALUES (
  'legacy-tombstone', '__cashier_guard_test_business__', '__cashier_guard_test_branch__',
  'CASHIER', NULL, NULL, false, now(),
  false, false, false, false, false, false, false, false, false, false, false,
  false, false, false, false, false
);
SELECT pg_temp.set_retirement_trigger(true);

INSERT INTO public.business_memberships (
  id, business_id, branch_id, role, auth_user_id, staff_identity_id,
  is_active, removed_at, can_create_sale, can_accept_cash, can_accept_online,
  can_create_credit_sale, can_use_coupon, can_issue_invoice, can_issue_receipt,
  can_refund, can_void, can_correct_amount, can_change_payment_method,
  can_view_broad_history, can_manage_products, can_view_reports,
  can_create_expense, can_view_customer_directory
) VALUES (
  'admin-fixture', '__cashier_guard_test_business__', '__cashier_guard_test_branch__',
  'ADMIN', '00000000-0000-0000-0000-000000000001', NULL, true, NULL,
  true, true, true, true, true, true, true, true, true, true, true,
  true, true, true, true, true
);

DO $test$
DECLARE
  v_active_cashiers bigint;
  v_trigger_count integer;
  v_constraint_count integer;
  v_principal_constraint_count integer;
  v_trigger_definition text;
  v_trigger_function oid;
  v_column text;
  v_negative_control text;
BEGIN
  v_negative_control := coalesce(current_setting('paid_due.guard_negative_control', true), '');

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
  IF v_trigger_count <> 1 AND v_negative_control <> 'trigger' THEN
    RAISE EXCEPTION 'FAIL: ADMIN-only membership trigger missing';
  END IF;

  IF v_trigger_count = 1 THEN
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
  END IF;

  SELECT count(*) INTO v_constraint_count
  FROM pg_constraint c
  JOIN pg_class r ON r.oid = c.conrelid
  JOIN pg_namespace n ON n.oid = r.relnamespace
  WHERE n.nspname = 'public'
    AND r.relname = 'business_memberships'
    AND c.conname = 'business_memberships_cashier_retirement_check'
    AND c.contype = 'c'
    AND c.convalidated;
  IF v_constraint_count <> 1 AND v_negative_control <> 'check' THEN
    RAISE EXCEPTION 'FAIL: validated CASHIER tombstone constraint missing';
  END IF;

  SELECT count(*) INTO v_principal_constraint_count
  FROM pg_constraint c
  JOIN pg_class r ON r.oid = c.conrelid
  JOIN pg_namespace n ON n.oid = r.relnamespace
  WHERE n.nspname = 'public'
    AND r.relname = 'business_memberships'
    AND c.conname = 'business_memberships_principal_role_check'
    AND c.contype = 'c'
    AND c.convalidated;
  IF v_principal_constraint_count <> 1 THEN
    RAISE EXCEPTION 'FAIL: validated principal-role constraint missing';
  END IF;

  -- These cases deliberately disable only the trigger so the production
  -- CHECK constraints on public.business_memberships, not a copied fixture
  -- predicate, are the mechanisms under test.
  PERFORM pg_temp.set_retirement_trigger(false);

  -- Staff identity makes the principal-role CHECK valid; active CASHIER state
  -- therefore isolates business_memberships_cashier_retirement_check.
  PERFORM pg_temp.assert_constraint_rejects(
    $sql$INSERT INTO public.business_memberships (
      id, business_id, branch_id, role, auth_user_id, staff_identity_id,
      is_active, removed_at
    ) VALUES (
      'check-active-rejected', '__cashier_guard_test_business__', '__cashier_guard_test_branch__',
      'CASHIER', NULL, '00000000-0000-0000-0000-000000000011', true, NULL
    )$sql$,
    'business_memberships_cashier_retirement_check',
    'FAIL: CHECK accepted active CASHIER row'
  );

  -- A permission grant on an otherwise-valid tombstone isolates the retirement CHECK.
  PERFORM pg_temp.assert_constraint_rejects(
    $sql$UPDATE public.business_memberships SET can_refund = true WHERE id = 'legacy-tombstone'$sql$,
    'business_memberships_cashier_retirement_check',
    'FAIL: CHECK accepted CASHIER permission'
  );

  -- CASHIER plus auth_user_id violates both principal shape and retirement
  -- policy; the actual PostgreSQL-reported constraint is asserted explicitly.
  PERFORM pg_temp.assert_constraint_rejects(
    $sql$INSERT INTO public.business_memberships (
      id, business_id, branch_id, role, auth_user_id, staff_identity_id,
      is_active, removed_at
    ) VALUES (
      'check-auth-user-rejected', '__cashier_guard_test_business__', '__cashier_guard_test_branch__',
      'CASHIER', '00000000-0000-0000-0000-000000000001', NULL, false, now()
    )$sql$,
    'business_memberships_cashier_retirement_check',
    'FAIL: CHECK accepted CASHIER auth_user_id'
  );

  -- A staff identity is allowed by the principal-role CHECK but forbidden by
  -- the retirement CHECK on a historical tombstone.
  PERFORM pg_temp.assert_constraint_rejects(
    $sql$INSERT INTO public.business_memberships (
      id, business_id, branch_id, role, auth_user_id, staff_identity_id,
      is_active, removed_at
    ) VALUES (
      'check-staff-id-rejected', '__cashier_guard_test_business__', '__cashier_guard_test_branch__',
      'CASHIER', NULL, '00000000-0000-0000-0000-000000000012', false, now()
    )$sql$,
    'business_memberships_cashier_retirement_check',
    'FAIL: CHECK accepted CASHIER staff_identity_id'
  );

  -- ADMIN requires auth_user_id; role != CASHIER means only the principal-role
  -- CHECK can reject this row.
  PERFORM pg_temp.assert_constraint_rejects(
    $sql$INSERT INTO public.business_memberships (
      id, business_id, branch_id, role, auth_user_id, staff_identity_id
    ) VALUES (
      'check-admin-principal-rejected', '__cashier_guard_test_business__', '__cashier_guard_test_branch__',
      'ADMIN', NULL, NULL
    )$sql$,
    'business_memberships_principal_role_check',
    'FAIL: principal-role CHECK accepted ADMIN without auth_user_id'
  );

  PERFORM pg_temp.set_retirement_trigger(true);

  -- The trigger must reject a new tombstone even though the production CHECK
  -- accepts its shape. This is also the trigger negative-control assertion.
  BEGIN
    INSERT INTO public.business_memberships (
      id, business_id, branch_id, role, auth_user_id, staff_identity_id,
      is_active, removed_at
    ) VALUES (
      'trigger-inactive-insert', '__cashier_guard_test_business__', '__cashier_guard_test_branch__',
      'CASHIER', NULL, NULL, false, now()
    );
    RAISE EXCEPTION 'FAIL: new CASHIER membership insert was accepted';
  EXCEPTION WHEN check_violation THEN
    GET STACKED DIAGNOSTICS v_negative_control = MESSAGE_TEXT;
    IF v_negative_control <> 'CASHIER role is retired; create ADMIN memberships only' THEN
      RAISE EXCEPTION 'FAIL: trigger rejection message mismatch: %', v_negative_control;
    END IF;
  END;

  -- Remaining trigger cases preserve the original exact trigger-message assertions.
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$INSERT INTO public.business_memberships (
      id, business_id, branch_id, role, auth_user_id, staff_identity_id,
      is_active, removed_at
    ) VALUES (
      'trigger-active-insert', '__cashier_guard_test_business__', '__cashier_guard_test_branch__',
      'CASHIER', NULL, '00000000-0000-0000-0000-000000000013', true, NULL
    )$sql$,
    'CASHIER role is retired; create ADMIN memberships only'
  );
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$INSERT INTO public.business_memberships (
      id, business_id, branch_id, role, auth_user_id, staff_identity_id,
      is_active, removed_at, can_create_sale
    ) VALUES (
      'trigger-permission-insert', '__cashier_guard_test_business__', '__cashier_guard_test_branch__',
      'CASHIER', NULL, NULL, false, now(), true
    )$sql$,
    'CASHIER role is retired; create ADMIN memberships only'
  );
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$INSERT INTO public.business_memberships (
      id, business_id, branch_id, role, auth_user_id, staff_identity_id,
      is_active, removed_at
    ) VALUES (
      'trigger-auth-user-insert', '__cashier_guard_test_business__', '__cashier_guard_test_branch__',
      'CASHIER', '00000000-0000-0000-0000-000000000001', NULL, false, now()
    )$sql$,
    'CASHIER role is retired; create ADMIN memberships only'
  );
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$INSERT INTO public.business_memberships (
      id, business_id, branch_id, role, auth_user_id, staff_identity_id,
      is_active, removed_at
    ) VALUES (
      'trigger-staff-identity-insert', '__cashier_guard_test_business__', '__cashier_guard_test_branch__',
      'CASHIER', NULL, '00000000-0000-0000-0000-000000000014', false, now()
    )$sql$,
    'CASHIER role is retired; create ADMIN memberships only'
  );
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$UPDATE public.business_memberships SET role='CASHIER', auth_user_id=NULL,
      is_active=false, removed_at=now(), can_create_sale=false, can_accept_cash=false,
      can_accept_online=false, can_create_credit_sale=false, can_use_coupon=false,
      can_issue_invoice=false, can_issue_receipt=false, can_refund=false, can_void=false,
      can_correct_amount=false, can_change_payment_method=false, can_view_broad_history=false,
      can_manage_products=false, can_view_reports=false, can_create_expense=false,
      can_view_customer_directory=false WHERE id='admin-fixture'$sql$,
    'CASHIER role is retired; role conversion to CASHIER is prohibited'
  );
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$UPDATE public.business_memberships SET is_active=true
      WHERE id='legacy-tombstone'$sql$,
    'Historical CASHIER tombstones cannot be activated or granted permissions'
  );
  PERFORM pg_temp.assert_cashier_trigger_rejects(
    $sql$UPDATE public.business_memberships SET can_refund=true
      WHERE id='legacy-tombstone'$sql$,
    'Historical CASHIER tombstones cannot be activated or granted permissions'
  );

  -- ADMIN inserts and permission updates remain accepted on the real table.
  INSERT INTO public.business_memberships (
    id, business_id, branch_id, role, auth_user_id, staff_identity_id,
    is_active, removed_at, can_create_sale, can_accept_cash, can_accept_online,
    can_create_credit_sale, can_use_coupon, can_issue_invoice, can_issue_receipt,
    can_refund, can_void, can_correct_amount, can_change_payment_method,
    can_view_broad_history, can_manage_products, can_view_reports,
    can_create_expense, can_view_customer_directory
  ) VALUES (
    'admin-insert-accepted', '__cashier_guard_test_business__', '__cashier_guard_test_branch__',
    'ADMIN', '00000000-0000-0000-0000-000000000001', NULL, true, NULL,
    true, true, true, true, true, true, true, true, true, true, true,
    true, true, true, true, true
  );
  UPDATE public.business_memberships SET can_refund=true, can_void=true
  WHERE id='admin-fixture';
  IF NOT EXISTS (
    SELECT 1 FROM public.business_memberships
    WHERE id='admin-fixture' AND can_refund AND can_void
  ) THEN
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

-- Disposable SQL Behavioral Gate regression contract for removed-active membership.
-- Uses real public.business_memberships rows and rolls back every fixture.
BEGIN;

INSERT INTO public.businesses (id, name, is_active)
VALUES ('admin-removal-authz-business', 'Admin removal authz test', true);
INSERT INTO public.branches (id, business_id, timezone_id, is_active)
VALUES ('admin-removal-authz-branch', 'admin-removal-authz-business', 'UTC', true);

INSERT INTO auth.users (id) VALUES
  ('00000000-0000-4000-8000-000000000101'),
  ('00000000-0000-4000-8000-000000000102'),
  ('00000000-0000-4000-8000-000000000103'),
  ('00000000-0000-4000-8000-000000000104'),
  ('00000000-0000-4000-8000-000000000105');

INSERT INTO public.profiles (auth_user_id, is_active, display_name, canonical_phone) VALUES
  ('00000000-0000-4000-8000-000000000101', true, 'Removed Admin', '+96890000101'),
  ('00000000-0000-4000-8000-000000000102', true, 'Active Admin', '+96890000102'),
  ('00000000-0000-4000-8000-000000000103', true, 'Inactive Admin', '+96890000103'),
  ('00000000-0000-4000-8000-000000000104', true, 'Current Admin', '+96890000104'),
  ('00000000-0000-4000-8000-000000000105', true, 'Target Admin', '+96890000105');

INSERT INTO public.business_memberships (
  id, business_id, branch_id, auth_user_id, local_user_id, role, is_active, removed_at,
  staff_identity_id, can_create_sale, can_accept_cash, can_accept_online,
  can_create_credit_sale, can_use_coupon, can_issue_invoice, can_issue_receipt,
  can_refund, can_void, can_correct_amount, can_change_payment_method,
  can_view_broad_history, can_manage_products, can_view_reports
) VALUES
  ('admin-removal-authz-removed', 'admin-removal-authz-business', 'admin-removal-authz-branch',
   '00000000-0000-4000-8000-000000000101', 'local-removed', 'ADMIN', true, now(), NULL,
   true,true,true,true,true,true,true,true,true,true,true,true,true,true),
  ('admin-removal-authz-active', 'admin-removal-authz-business', 'admin-removal-authz-branch',
   '00000000-0000-4000-8000-000000000102', 'local-active', 'ADMIN', true, NULL, NULL,
   true,true,true,true,true,true,true,true,true,true,true,true,true,true),
  ('admin-removal-authz-inactive', 'admin-removal-authz-business', 'admin-removal-authz-branch',
   '00000000-0000-4000-8000-000000000103', 'local-inactive', 'ADMIN', false, NULL, NULL,
   true,true,true,true,true,true,true,true,true,true,true,true,true,true),
  ('admin-removal-authz-viewer', 'admin-removal-authz-business', 'admin-removal-authz-branch',
   '00000000-0000-4000-8000-000000000104', 'local-viewer', 'ADMIN', true, NULL, NULL,
   true,true,true,true,true,true,true,true,true,true,true,true,true,true),
  ('admin-removal-authz-target', 'admin-removal-authz-business', 'admin-removal-authz-branch',
   '00000000-0000-4000-8000-000000000105', 'local-target', 'ADMIN', true, now(), NULL,
   true,true,true,true,true,true,true,true,true,true,true,true,true,true);

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000101', true);
DO $test$ DECLARE v_rows integer; BEGIN
  SELECT count(*) INTO v_rows FROM public.restore_admin_by_auth_id();
  IF v_rows <> 0 THEN RAISE EXCEPTION 'FAIL: active ADMIN with removed_at set returned % restore rows; expected 0', v_rows; END IF;
END $test$;

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000102', true);
DO $test$ DECLARE v_rows integer; BEGIN
  SELECT count(*) INTO v_rows FROM public.restore_admin_by_auth_id();
  IF v_rows <> 1 THEN RAISE EXCEPTION 'FAIL: active ADMIN with removed_at NULL returned % restore rows; expected 1', v_rows; END IF;
END $test$;

SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000103', true);
DO $test$ DECLARE v_rows integer; BEGIN
  SELECT count(*) INTO v_rows FROM public.restore_admin_by_auth_id();
  IF v_rows <> 0 THEN RAISE EXCEPTION 'FAIL: inactive ADMIN returned % restore rows; expected 0', v_rows; END IF;
END $test$;

-- A valid membership plus a removed-active duplicate must still yield one row.
INSERT INTO public.business_memberships (
  id, business_id, branch_id, auth_user_id, local_user_id, role, is_active, removed_at,
  staff_identity_id, can_create_sale, can_accept_cash, can_accept_online,
  can_create_credit_sale, can_use_coupon, can_issue_invoice, can_issue_receipt,
  can_refund, can_void, can_correct_amount, can_change_payment_method,
  can_view_broad_history, can_manage_products, can_view_reports
) VALUES (
  'admin-removal-authz-removed-duplicate', 'admin-removal-authz-business', 'admin-removal-authz-branch',
  '00000000-0000-4000-8000-000000000102', 'local-active-removed-duplicate', 'ADMIN', true, now(), NULL,
  true,true,true,true,true,true,true,true,true,true,true,true,true,true
);
DO $test$ DECLARE v_rows integer; BEGIN
  SELECT count(*) INTO v_rows FROM public.restore_admin_by_auth_id();
  IF v_rows <> 1 THEN RAISE EXCEPTION 'FAIL: mixed active/removed memberships returned % rows; expected exactly 1', v_rows; END IF;
END $test$;

-- Financial ingest must reject the removed-active row before payload validation or writes.
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000101', true);
DO $test$ DECLARE v_state text; v_message text; BEGIN
  BEGIN
    PERFORM * FROM public.ingest_financial_envelope(
      'admin-removal-authz-business', 'removed-authz-operation', 'admin-removal-authz-branch',
      'admin-removal-authz-removed', 'device-test', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
    );
    RAISE EXCEPTION 'FAIL: ingest accepted removed-active ADMIN membership';
  EXCEPTION WHEN SQLSTATE '42501' THEN
    GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_message = MESSAGE_TEXT;
    IF v_state <> '42501' OR v_message <> 'INVALID_ADMIN_MEMBERSHIP' THEN
      RAISE EXCEPTION 'FAIL: expected 42501/INVALID_ADMIN_MEMBERSHIP, got %/%', v_state, v_message;
    END IF;
  END;
END $test$;

-- A removed target membership must not expose that profile to another active Admin.
SELECT set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000104', true);
SET LOCAL ROLE authenticated;
DO $test$ DECLARE v_rows integer; BEGIN
  SELECT count(*) INTO v_rows FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000105';
  IF v_rows <> 0 THEN RAISE EXCEPTION 'FAIL: shared-business profile policy exposed removed-active target; expected 0 rows, got %', v_rows; END IF;
END $test$;
RESET ROLE;

-- Positive control: the same policy still exposes a target with a live membership.
UPDATE public.business_memberships SET removed_at = NULL
WHERE id = 'admin-removal-authz-target';
SET LOCAL ROLE authenticated;
DO $test$ DECLARE v_rows integer; BEGIN
  SELECT count(*) INTO v_rows FROM public.profiles
  WHERE auth_user_id = '00000000-0000-4000-8000-000000000105';
  IF v_rows <> 1 THEN RAISE EXCEPTION 'FAIL: shared-business profile policy hid active target; expected 1 row, got %', v_rows; END IF;
END $test$;
RESET ROLE;

ROLLBACK;

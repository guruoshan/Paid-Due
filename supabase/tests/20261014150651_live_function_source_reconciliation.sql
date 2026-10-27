-- Runs only against the disposable PostgreSQL fixture in notification-migration-gate.
-- Verifies the previously untracked list_admin_cashiers RPC is branch-isolated and
-- keeps its deployed execute grants; also checks the financial ingest grants.
UPDATE private.staff_identities
SET display_name = 'Cashier One',
    canonical_phone = '+96890000001',
    is_active = true
WHERE id = '11111111-1111-4111-8111-111111111111';

UPDATE public.business_memberships
SET local_user_id = 'cashier-local-1'
WHERE id = 'membership-test';

INSERT INTO public.profiles (auth_user_id, display_name, is_active)
VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'Test Admin', true);

INSERT INTO public.business_memberships (
  id, business_id, branch_id, auth_user_id, role, is_active
) VALUES (
  'admin-membership-test', 'biz-test', 'branch-test',
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'ADMIN', true
);

INSERT INTO public.branches (
  id, business_id, name, code, country_code, currency_code,
  currency_decimal_places, currency_symbol, timezone_id,
  tax_rounding_mode, default_tax_rate_bps, is_active
) VALUES (
  'branch-other', 'biz-test', 'Other Branch', 'OTHER', 'OM', 'OMR',
  3, 'OMR', 'UTC', 'HALF_UP', 0, true
);

INSERT INTO private.staff_identities (id, is_active, display_name, canonical_phone)
VALUES ('22222222-2222-4222-8222-222222222222', true, 'Other Cashier', '+96890000002');

INSERT INTO public.business_memberships (
  id, business_id, branch_id, local_user_id, staff_identity_id, role, is_active
) VALUES (
  'membership-other', 'biz-test', 'branch-other', 'cashier-local-other',
  '22222222-2222-4222-8222-222222222222', 'CASHIER', true
);

DO $$
DECLARE
  v_count integer;
  v_list regprocedure := 'public.list_admin_cashiers()'::regprocedure;
  v_ingest regprocedure := 'public.ingest_financial_envelope(text,text,text,text,text,text,integer,timestamptz,text,date,text,text,jsonb)'::regprocedure;
  v_payload jsonb;
  v_collision_payload jsonb;
  v_canonical_text text;
  v_digest text;
  v_occurred_at timestamptz;
  v_local_date date;
  v_result record;
BEGIN
  PERFORM set_config('request.jwt.claim.sub', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', true);

  SELECT count(*) INTO v_count FROM public.list_admin_cashiers();
  IF v_count <> 1 THEN
    RAISE EXCEPTION 'Expected exactly one cashier for the authenticated admin branch, got %', v_count;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.list_admin_cashiers()
    WHERE local_user_id = 'cashier-local-1'
      AND membership_id = 'membership-test'
      AND branch_id = 'branch-test'
      AND display_name = 'Cashier One'
      AND canonical_phone = '+96890000001'
      AND is_active
  ) THEN
    RAISE EXCEPTION 'Active cashier row missing from admin directory';
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.list_admin_cashiers()
    WHERE branch_id = 'branch-other'
  ) THEN
    RAISE EXCEPTION 'Admin cashier directory leaked a different branch';
  END IF;

  IF NOT has_function_privilege('authenticated', v_list, 'EXECUTE')
     OR has_function_privilege('anon', v_list, 'EXECUTE')
     OR has_function_privilege('service_role', v_list, 'EXECUTE') THEN
    RAISE EXCEPTION 'list_admin_cashiers execute grants differ from deployed policy';
  END IF;

  IF NOT has_function_privilege('authenticated', v_ingest, 'EXECUTE')
     OR NOT has_function_privilege('service_role', v_ingest, 'EXECUTE')
     OR has_function_privilege('anon', v_ingest, 'EXECUTE') THEN
    RAISE EXCEPTION 'ingest_financial_envelope execute grants differ from deployed policy';
  END IF;

  -- Validate the deployed uppercase acknowledgement contract, duplicate replay,
  -- and collision rejection so a source-capture migration cannot regress sync.
  v_occurred_at := clock_timestamp() - interval '1 second';
  v_local_date := (v_occurred_at AT TIME ZONE 'UTC')::date;
  v_payload := jsonb_build_object(
    'operation_id', 'source-capture-test-op',
    'business_id', 'biz-test',
    'branch_id', 'branch-test',
    'device_id', 'source-capture-device',
    'operation_type', 'CLOSE_CASH_SHIFT',
    'command_schema_version', 1,
    'occurred_at', floor(extract(epoch FROM v_occurred_at) * 1000)::bigint
  );
  v_canonical_text := v_payload::text;
  v_digest := encode(extensions.digest(convert_to(v_canonical_text, 'UTF8'), 'sha256'), 'hex');

  SELECT * INTO v_result
  FROM public.ingest_financial_envelope(
    'biz-test', 'source-capture-test-op', 'branch-test', 'admin-membership-test',
    'source-capture-device', 'CLOSE_CASH_SHIFT', 1, v_occurred_at, 'UTC',
    v_local_date, v_canonical_text, v_digest, v_payload
  );
  IF v_result.status <> 'ACKNOWLEDGED' OR v_result.duplicate THEN
    RAISE EXCEPTION 'First financial-envelope upload must be ACKNOWLEDGED and non-duplicate';
  END IF;

  SELECT * INTO v_result
  FROM public.ingest_financial_envelope(
    'biz-test', 'source-capture-test-op', 'branch-test', 'admin-membership-test',
    'source-capture-device', 'CLOSE_CASH_SHIFT', 1, v_occurred_at, 'UTC',
    v_local_date, v_canonical_text, v_digest, v_payload
  );
  IF v_result.status <> 'ACKNOWLEDGED' OR NOT v_result.duplicate THEN
    RAISE EXCEPTION 'Exact financial-envelope replay must be ACKNOWLEDGED as duplicate';
  END IF;

  v_collision_payload := jsonb_build_object(
    'operation_id', 'source-capture-test-op',
    'business_id', 'biz-test',
    'collision', true
  );
  v_canonical_text := v_collision_payload::text;
  v_digest := encode(extensions.digest(convert_to(v_canonical_text, 'UTF8'), 'sha256'), 'hex');
  BEGIN
    SELECT * INTO v_result
    FROM public.ingest_financial_envelope(
      'biz-test', 'source-capture-test-op', 'branch-test', 'admin-membership-test',
      'source-capture-device', 'CLOSE_CASH_SHIFT', 1, v_occurred_at, 'UTC',
      v_local_date, v_canonical_text, v_digest, v_collision_payload
    );
    RAISE EXCEPTION 'Expected OPERATION_COLLISION';
  EXCEPTION WHEN OTHERS THEN
    IF SQLERRM <> 'OPERATION_COLLISION' THEN
      RAISE;
    END IF;
  END;

  SELECT count(*) INTO v_count
  FROM private.financial_operation_envelopes
  WHERE business_id = 'biz-test' AND operation_id = 'source-capture-test-op';
  IF v_count <> 1 THEN
    RAISE EXCEPTION 'Idempotent upload/collision handling changed financial envelope count';
  END IF;
END;
$$;

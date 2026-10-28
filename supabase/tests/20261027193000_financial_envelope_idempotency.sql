-- Database-level replay, collision, and transport/canonical metadata consistency tests.
-- RUN ONLY against the disposable PostgreSQL fixture used by notification-migration-gate.
-- Example: psql "$TEST_DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/tests/20261027193000_financial_envelope_idempotency.sql
-- Requires fixture rows biz-test / branch-test / admin-membership-test and the test JWT subject.
BEGIN;

DO $test$
DECLARE
  v_occurred_at timestamptz := date_trunc('second', clock_timestamp() - interval '2 seconds');
  v_local_date date;
  v_payload jsonb;
  v_changed_payload jsonb;
  v_canonical_text text;
  v_digest text;
  v_result record;
  v_count integer;
  v_saved_text text;
  v_saved_digest text;
  v_operation_id text;
  v_app_occurred_ms bigint;
  v_fraction integer;
  v_audit_count_before integer;
BEGIN
  PERFORM set_config('request.jwt.claim.sub', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', true);
  v_local_date := (v_occurred_at AT TIME ZONE 'UTC')::date;

  -- Direct RPC security: an unauthenticated request must not write financial data.
  IF has_function_privilege(
       'anon',
       'public.ingest_financial_envelope(text,text,text,text,text,text,integer,timestamptz,text,date,text,text,jsonb)'::regprocedure,
       'EXECUTE'
     ) THEN
    RAISE EXCEPTION 'FAIL: anon must not be able to execute financial ingest';
  END IF;

  v_operation_id := 'idempotency-no-session-test-op';
  v_payload := jsonb_build_object(
    'operation_id', v_operation_id,
    'business_id', 'biz-test',
    'branch_id', 'branch-test',
    'device_id', 'idempotency-auth-test-device',
    'operation_type', 'CLOSE_CASH_SHIFT',
    'command_schema_version', 1,
    'occurred_at', floor(extract(epoch FROM v_occurred_at) * 1000)::bigint
  );
  v_canonical_text := v_payload::text;
  v_digest := encode(extensions.digest(convert_to(v_canonical_text, 'UTF8'), 'sha256'), 'hex');

  PERFORM set_config('request.jwt.claim.sub', '', true);
  BEGIN
    PERFORM * FROM public.ingest_financial_envelope(
      'biz-test', v_operation_id, 'branch-test', 'admin-membership-test',
      'idempotency-auth-test-device', 'CLOSE_CASH_SHIFT', 1, v_occurred_at, 'UTC',
      v_local_date, v_canonical_text, v_digest, v_payload
    );
    RAISE EXCEPTION 'FAIL: unauthenticated direct RPC request was accepted';
  EXCEPTION WHEN SQLSTATE '42501' THEN
    NULL;
  END;
  SELECT count(*) INTO v_count FROM private.financial_operation_envelopes
  WHERE business_id = 'biz-test' AND operation_id = v_operation_id;
  IF v_count <> 0 THEN
    RAISE EXCEPTION 'FAIL: unauthenticated RPC wrote a financial envelope';
  END IF;

  -- An authenticated user without Admin membership must also be rejected.
  PERFORM set_config('request.jwt.claim.sub', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', true);
  BEGIN
    PERFORM * FROM public.ingest_financial_envelope(
      'biz-test', v_operation_id, 'branch-test', 'admin-membership-test',
      'idempotency-auth-test-device', 'CLOSE_CASH_SHIFT', 1, v_occurred_at, 'UTC',
      v_local_date, v_canonical_text, v_digest, v_payload
    );
    RAISE EXCEPTION 'FAIL: non-member direct RPC request was accepted';
  EXCEPTION WHEN SQLSTATE '42501' THEN
    NULL;
  END;
  SELECT count(*) INTO v_count FROM private.financial_operation_envelopes
  WHERE business_id = 'biz-test' AND operation_id = v_operation_id;
  IF v_count <> 0 THEN
    RAISE EXCEPTION 'FAIL: non-member RPC wrote a financial envelope';
  END IF;

  PERFORM set_config('request.jwt.claim.sub', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', true);

  -- 1. Exact replay: one stored envelope, duplicate acknowledgement on replay.
  v_operation_id := 'idempotency-replay-test-op';
  v_payload := jsonb_build_object(
    'operation_id', v_operation_id,
    'business_id', 'biz-test',
    'branch_id', 'branch-test',
    'device_id', 'idempotency-test-device',
    'operation_type', 'CLOSE_CASH_SHIFT',
    'command_schema_version', 1,
    'occurred_at', floor(extract(epoch FROM v_occurred_at) * 1000)::bigint
  );
  v_canonical_text := v_payload::text;
  v_digest := encode(extensions.digest(convert_to(v_canonical_text, 'UTF8'), 'sha256'), 'hex');

  SELECT * INTO v_result FROM public.ingest_financial_envelope(
    'biz-test', v_operation_id, 'branch-test', 'admin-membership-test',
    'idempotency-test-device', 'CLOSE_CASH_SHIFT', 1, v_occurred_at, 'UTC',
    v_local_date, v_canonical_text, v_digest, v_payload
  );
  IF v_result.status <> 'ACKNOWLEDGED' OR v_result.operation_id <> v_operation_id
     OR v_result.canonical_sha256 <> v_digest OR v_result.duplicate THEN
    RAISE EXCEPTION 'FAIL: first upload must acknowledge as non-duplicate';
  END IF;

  SELECT * INTO v_result FROM public.ingest_financial_envelope(
    'biz-test', v_operation_id, 'branch-test', 'admin-membership-test',
    'idempotency-test-device', 'CLOSE_CASH_SHIFT', 1, v_occurred_at, 'UTC',
    v_local_date, v_canonical_text, v_digest, v_payload
  );
  IF v_result.status <> 'ACKNOWLEDGED' OR NOT v_result.duplicate THEN
    RAISE EXCEPTION 'FAIL: exact replay must acknowledge with duplicate=true';
  END IF;

  SELECT count(*) INTO v_count FROM private.financial_operation_envelopes
  WHERE business_id = 'biz-test' AND operation_id = v_operation_id;
  IF v_count <> 1 THEN
    RAISE EXCEPTION 'FAIL: exact replay stored % rows, expected 1', v_count;
  END IF;

  -- 2. Reusing the same key with changed content/digest must collide and preserve stored data.
  SELECT canonical_text, canonical_sha256 INTO v_saved_text, v_saved_digest
  FROM private.financial_operation_envelopes
  WHERE business_id = 'biz-test' AND operation_id = v_operation_id;

  v_changed_payload := v_payload || jsonb_build_object('collision_test_marker', true);
  v_canonical_text := v_changed_payload::text;
  v_digest := encode(extensions.digest(convert_to(v_canonical_text, 'UTF8'), 'sha256'), 'hex');
  SELECT count(*) INTO v_audit_count_before FROM private.collision_audit
  WHERE operation_id = v_operation_id;
  BEGIN
    SELECT * INTO v_result FROM public.ingest_financial_envelope(
      'biz-test', v_operation_id, 'branch-test', 'admin-membership-test',
      'idempotency-test-device', 'CLOSE_CASH_SHIFT', 1, v_occurred_at, 'UTC',
      v_local_date, v_canonical_text, v_digest, v_changed_payload
    );
    IF v_result.status <> 'OPERATION_COLLISION' OR v_result.operation_id <> v_operation_id
       OR v_result.canonical_sha256 <> v_digest OR v_result.duplicate THEN
      RAISE EXCEPTION 'FAIL: changed content must return OPERATION_COLLISION';
    END IF;
    SELECT count(*) INTO v_count FROM private.collision_audit
    WHERE operation_id = v_operation_id;
    IF v_count <> v_audit_count_before + 1 THEN
      RAISE EXCEPTION 'FAIL: collision audit count increased by %, expected exactly 1',
        v_count - v_audit_count_before;
    END IF;
  END;

  SELECT count(*) INTO v_count FROM private.financial_operation_envelopes
  WHERE business_id = 'biz-test' AND operation_id = v_operation_id;
  IF v_count <> 1 OR NOT EXISTS (
    SELECT 1 FROM private.financial_operation_envelopes
    WHERE business_id = 'biz-test' AND operation_id = v_operation_id
      AND canonical_text = v_saved_text AND canonical_sha256 = v_saved_digest
  ) THEN
    RAISE EXCEPTION 'FAIL: collision modified or duplicated the stored envelope';
  END IF;

  -- 3. Metadata mismatch must be rejected before insertion; canonical text remains unchanged.
  v_operation_id := 'idempotency-metadata-mismatch-test-op';
  v_payload := jsonb_build_object(
    'operation_id', v_operation_id,
    'business_id', 'biz-test',
    'branch_id', 'branch-test',
    'device_id', 'idempotency-test-device',
    'operation_type', 'CLOSE_CASH_SHIFT',
    'command_schema_version', 1,
    'occurred_at', floor(extract(epoch FROM v_occurred_at) * 1000)::bigint
  );
  v_canonical_text := v_payload::text;
  v_digest := encode(extensions.digest(convert_to(v_canonical_text, 'UTF8'), 'sha256'), 'hex');

  BEGIN
    PERFORM * FROM public.ingest_financial_envelope(
      'biz-test', v_operation_id, 'branch-test', 'admin-membership-test',
      'idempotency-test-device', 'RECORD_SALE', 2, v_occurred_at, 'UTC',
      v_local_date, v_canonical_text, v_digest, v_payload
    );
    RAISE EXCEPTION 'FAIL: expected ENVELOPE_METADATA_MISMATCH';
  EXCEPTION WHEN OTHERS THEN
    IF SQLERRM <> 'ENVELOPE_METADATA_MISMATCH' THEN RAISE; END IF;
  END;

  SELECT count(*) INTO v_count FROM private.financial_operation_envelopes
  WHERE business_id = 'biz-test' AND operation_id = v_operation_id;
  IF v_count <> 0 THEN
    RAISE EXCEPTION 'FAIL: metadata mismatch stored % rows, expected 0', v_count;
  END IF;

  -- 4. App-style compact JSON envelope for millisecond timestamps whose
  -- fractional millisecond digits are .001, .999, .123 and .500 seconds.
  -- RPC timestamp values are produced from the same epoch milliseconds.
  FOREACH v_fraction IN ARRAY ARRAY[1, 999, 123, 500] LOOP
    v_operation_id := format('idempotency-app-style-ms-%s-test-op', v_fraction);
    v_app_occurred_ms :=
      floor(extract(epoch FROM date_trunc('second', clock_timestamp())) * 1000)::bigint
      + v_fraction;
    v_occurred_at := to_timestamp(v_app_occurred_ms::numeric / 1000);
    v_local_date := (v_occurred_at AT TIME ZONE 'UTC')::date;
    v_canonical_text := format(
      '{"operation_id":"%s","business_id":"biz-test","branch_id":"branch-test","actor_user_id":"app-style-admin-user","device_id":"idempotency-app-style-device","operation_type":"CLOSE_CASH_SHIFT","occurred_at":%s,"command_schema_version":1,"request_fingerprint":"sha256:app-style-request-fingerprint","intent_payload":"{}","subject_type":"CASH_SHIFT","subject_id":"app-style-shift","transaction_id":"","cash_shift_id":"app-style-shift"}',
      v_operation_id, v_app_occurred_ms
    );
    v_payload := v_canonical_text::jsonb;
    v_digest := encode(extensions.digest(convert_to(v_canonical_text, 'UTF8'), 'sha256'), 'hex');

    SELECT * INTO v_result FROM public.ingest_financial_envelope(
      'biz-test', v_operation_id, 'branch-test', 'admin-membership-test',
      'idempotency-app-style-device', 'CLOSE_CASH_SHIFT', 1, v_occurred_at, 'UTC',
      v_local_date, v_canonical_text, v_digest, v_payload
    );
    IF v_result.status <> 'ACKNOWLEDGED' OR v_result.duplicate THEN
      RAISE EXCEPTION 'FAIL: app-style millisecond envelope with fraction % must be accepted', v_fraction;
    END IF;

    SELECT count(*) INTO v_count FROM private.financial_operation_envelopes AS envelope
    WHERE envelope.business_id = 'biz-test' AND envelope.operation_id = v_operation_id;
    IF v_count <> 1 THEN
      RAISE EXCEPTION 'FAIL: app-style envelope fraction % stored % rows, expected 1', v_fraction, v_count;
    END IF;
  END LOOP;

  RAISE NOTICE 'PASS: exact replay acknowledges duplicate and stores one row';
  RAISE NOTICE 'PASS: changed content collides and stored row is unchanged';
  RAISE NOTICE 'PASS: transport/canonical metadata mismatch rejected before storage';
  RAISE NOTICE 'PASS: app-style millisecond fractions .001, .999, .123 and .500 accepted';
END
$test$;

ROLLBACK;

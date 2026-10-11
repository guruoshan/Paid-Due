-- Behavioral tests run after the real migration has been applied to local Supabase Postgres.
-- Every assertion exercises the installed PostgreSQL function, not string inspection.
CREATE TEMP TABLE _ensure_pg_temp_schema (x integer);
DROP TABLE _ensure_pg_temp_schema;

CREATE OR REPLACE FUNCTION pg_temp.assert_tender_denied(p_category text, p_operation_id text)
RETURNS void
LANGUAGE plpgsql
AS $test$
DECLARE
  v_payload jsonb;
  v_text text;
  v_hash text;
  v_seen_denial boolean := false;
BEGIN
  UPDATE public.business_memberships
  SET can_create_sale = true,
      can_accept_cash = (p_category <> 'CASH'),
      can_accept_online = (p_category <> 'ONLINE'),
      can_create_credit_sale = (p_category <> 'CREDIT'),
      can_use_coupon = (p_category <> 'COUPON')
  WHERE id = 'membership-test';

  v_payload := jsonb_build_object(
    'operation_id', p_operation_id,
    'business_id', 'biz-test',
    'settlements', jsonb_build_array(jsonb_build_object('category', p_category))
  );
  v_text := v_payload::text;
  v_hash := encode(extensions.digest(convert_to(v_text, 'UTF8'), 'sha256'), 'hex');

  BEGIN
    PERFORM *
    FROM public.ingest_cashier_financial_envelope(
      'biz-test', p_operation_id, 'branch-test', 'membership-test',
      'device-test', 'RECORD_SALE', 1, now(), 'UTC', current_date,
      v_text, v_hash, v_payload
    );
  EXCEPTION WHEN SQLSTATE '42501' THEN
    IF SQLERRM <> 'OPERATION_NOT_PERMITTED' THEN
      RAISE EXCEPTION 'FAIL %: expected OPERATION_NOT_PERMITTED, got %', p_category, SQLERRM;
    END IF;
    v_seen_denial := true;
  END;

  IF NOT v_seen_denial THEN
    RAISE EXCEPTION 'FAIL %: unauthorized tender was accepted', p_category;
  END IF;
  RAISE NOTICE 'PASS % permission denial | expected=OPERATION_NOT_PERMITTED | actual=OPERATION_NOT_PERMITTED', p_category;
END;
$test$;

DO $test$
DECLARE
  v_payload jsonb;
  v_text text;
  v_hash text;
  v_result record;
  v_seen_denial boolean;
BEGIN
  -- 1-4: one denied tender per permission, with all other tender permissions enabled.
  PERFORM pg_temp.assert_tender_denied('CASH', 'test-cash-denial');
  PERFORM pg_temp.assert_tender_denied('ONLINE', 'test-online-denial');
  PERFORM pg_temp.assert_tender_denied('CREDIT', 'test-credit-denial');
  PERFORM pg_temp.assert_tender_denied('COUPON', 'test-coupon-denial');

  UPDATE public.business_memberships
  SET can_create_sale = true, can_accept_cash = true, can_accept_online = true,
      can_create_credit_sale = true, can_use_coupon = true
  WHERE id = 'membership-test';

  -- 5: malformed settlement row must fail closed.
  v_payload := jsonb_build_object(
    'operation_id', 'test-malformed-settlement',
    'business_id', 'biz-test',
    'settlements', jsonb_build_array('not-an-object')
  );
  v_text := v_payload::text;
  v_hash := encode(extensions.digest(convert_to(v_text, 'UTF8'), 'sha256'), 'hex');
  v_seen_denial := false;
  BEGIN
    PERFORM * FROM public.ingest_cashier_financial_envelope(
      'biz-test', 'test-malformed-settlement', 'branch-test', 'membership-test',
      'device-test', 'RECORD_SALE', 1, now(), 'UTC', current_date,
      v_text, v_hash, v_payload
    );
  EXCEPTION WHEN SQLSTATE '42501' THEN
    IF SQLERRM <> 'OPERATION_NOT_PERMITTED' THEN
      RAISE EXCEPTION 'FAIL malformed settlement: unexpected error %', SQLERRM;
    END IF;
    v_seen_denial := true;
  END;
  IF NOT v_seen_denial THEN RAISE EXCEPTION 'FAIL malformed settlement: accepted'; END IF;
  RAISE NOTICE 'PASS malformed settlement | expected=OPERATION_NOT_PERMITTED | actual=OPERATION_NOT_PERMITTED';

  -- 6: a settlement object with a missing category must fail closed (SQL NULL edge case).
  v_payload := jsonb_build_object(
    'operation_id', 'test-missing-category',
    'business_id', 'biz-test',
    'settlements', jsonb_build_array(jsonb_build_object())
  );
  v_text := v_payload::text;
  v_hash := encode(extensions.digest(convert_to(v_text, 'UTF8'), 'sha256'), 'hex');
  v_seen_denial := false;
  BEGIN
    PERFORM * FROM public.ingest_cashier_financial_envelope(
      'biz-test', 'test-missing-category', 'branch-test', 'membership-test',
      'device-test', 'RECORD_SALE', 1, now(), 'UTC', current_date,
      v_text, v_hash, v_payload
    );
  EXCEPTION WHEN SQLSTATE '42501' THEN
    IF SQLERRM <> 'OPERATION_NOT_PERMITTED' THEN
      RAISE EXCEPTION 'FAIL missing settlement category: unexpected error %', SQLERRM;
    END IF;
    v_seen_denial := true;
  END;
  IF NOT v_seen_denial THEN RAISE EXCEPTION 'FAIL missing settlement category: accepted'; END IF;
  RAISE NOTICE 'PASS missing settlement category | expected=OPERATION_NOT_PERMITTED | actual=OPERATION_NOT_PERMITTED';

  -- 7: unknown tender category must fail closed.
  v_payload := jsonb_build_object(
    'operation_id', 'test-unknown-category',
    'business_id', 'biz-test',
    'settlements', jsonb_build_array(jsonb_build_object('category', 'CRYPTO'))
  );
  v_text := v_payload::text;
  v_hash := encode(extensions.digest(convert_to(v_text, 'UTF8'), 'sha256'), 'hex');
  v_seen_denial := false;
  BEGIN
    PERFORM * FROM public.ingest_cashier_financial_envelope(
      'biz-test', 'test-unknown-category', 'branch-test', 'membership-test',
      'device-test', 'RECORD_SALE', 1, now(), 'UTC', current_date,
      v_text, v_hash, v_payload
    );
  EXCEPTION WHEN SQLSTATE '42501' THEN
    IF SQLERRM <> 'OPERATION_NOT_PERMITTED' THEN
      RAISE EXCEPTION 'FAIL unknown category: unexpected error %', SQLERRM;
    END IF;
    v_seen_denial := true;
  END;
  IF NOT v_seen_denial THEN RAISE EXCEPTION 'FAIL unknown category: accepted'; END IF;
  RAISE NOTICE 'PASS unknown settlement category | expected=OPERATION_NOT_PERMITTED | actual=OPERATION_NOT_PERMITTED';

  -- 8: first operation is new; exact replay is acknowledged as duplicate.
  v_payload := jsonb_build_object(
    'operation_id', 'test-duplicate-operation',
    'business_id', 'biz-test',
    'settlements', jsonb_build_array(jsonb_build_object('category', 'CASH'))
  );
  v_text := v_payload::text;
  v_hash := encode(extensions.digest(convert_to(v_text, 'UTF8'), 'sha256'), 'hex');
  SELECT * INTO v_result FROM public.ingest_cashier_financial_envelope(
    'biz-test', 'test-duplicate-operation', 'branch-test', 'membership-test',
    'device-test', 'RECORD_SALE', 1, now(), 'UTC', current_date,
    v_text, v_hash, v_payload
  );
  IF v_result.status <> 'ACKNOWLEDGED' OR v_result.duplicate IS DISTINCT FROM false THEN
    RAISE EXCEPTION 'FAIL duplicate test first insert: expected ACKNOWLEDGED/false, got %/%',
      v_result.status, v_result.duplicate;
  END IF;

  SELECT * INTO v_result FROM public.ingest_cashier_financial_envelope(
    'biz-test', 'test-duplicate-operation', 'branch-test', 'membership-test',
    'device-test', 'RECORD_SALE', 1, now(), 'UTC', current_date,
    v_text, v_hash, v_payload
  );
  IF v_result.status <> 'ACKNOWLEDGED' OR v_result.duplicate IS DISTINCT FROM true THEN
    RAISE EXCEPTION 'FAIL duplicate replay: expected ACKNOWLEDGED/true, got %/%',
      v_result.status, v_result.duplicate;
  END IF;
  RAISE NOTICE 'PASS duplicate operation ID | expected=ACKNOWLEDGED/duplicate=true on replay | actual=ACKNOWLEDGED/duplicate=true';
END
$test$;

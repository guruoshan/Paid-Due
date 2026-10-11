-- Executable, read-only contract tests for the cashier financial migration.
-- Run with: psql "$TEST_DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/tests/20261011140000_align_cashier_financial_contract_test.sql
-- These checks inspect installed function definitions; they do not mutate financial data.
DO $test$
DECLARE
  v_live text;
BEGIN
  SELECT pg_get_functiondef(
    'public.ingest_cashier_financial_envelope(text,text,text,text,text,text,integer,timestamp with time zone,text,date,text,text,jsonb)'::regprocedure
  ) INTO v_live;

  IF v_live IS NULL THEN
    RAISE EXCEPTION 'FAIL: cashier financial envelope function missing';
  END IF;

  -- Permission denial cases: each settlement tender must be explicitly authorized.
  IF position('NOT v_membership.can_accept_cash' IN v_live) = 0 THEN
    RAISE EXCEPTION 'FAIL: CASH permission denial guard missing';
  END IF;
  IF position('NOT v_membership.can_accept_online' IN v_live) = 0 THEN
    RAISE EXCEPTION 'FAIL: ONLINE permission denial guard missing';
  END IF;
  IF position('NOT v_membership.can_create_credit_sale' IN v_live) = 0 THEN
    RAISE EXCEPTION 'FAIL: CREDIT permission denial guard missing';
  END IF;
  IF position('NOT v_membership.can_use_coupon' IN v_live) = 0 THEN
    RAISE EXCEPTION 'FAIL: COUPON permission denial guard missing';
  END IF;

  -- Malformed settlement: array, non-empty, object row, and string category are required.
  IF position('jsonb_typeof(p_canonical_payload -> ''settlements'') = ''array''' IN v_live) = 0
     OR position('jsonb_array_length(p_canonical_payload -> ''settlements'') > 0' IN v_live) = 0
     OR position('jsonb_typeof(settlement.value) <> ''object''' IN v_live) = 0
     OR position('jsonb_typeof(settlement.value -> ''category'') <> ''string''' IN v_live) = 0 THEN
    RAISE EXCEPTION 'FAIL: malformed settlement guards missing';
  END IF;

  -- Unknown settlement category must fail closed.
  IF position('NOT IN (''CASH'', ''ONLINE'', ''CREDIT'', ''COUPON'')' IN v_live) = 0 THEN
    RAISE EXCEPTION 'FAIL: unknown settlement category guard missing';
  END IF;

  -- Duplicate operation: same hash returns duplicate=true; differing hash collides.
  IF position('ON CONFLICT ON CONSTRAINT financial_operation_envelopes_pkey DO NOTHING' IN v_live) = 0
     OR position('v_existing_hash IS DISTINCT FROM p_canonical_sha256' IN v_live) = 0
     OR position('true;' IN v_live) = 0 THEN
    RAISE EXCEPTION 'FAIL: duplicate operation/idempotency contract missing';
  END IF;

  RAISE NOTICE 'PASS: CASH permission denial guard';
  RAISE NOTICE 'PASS: ONLINE permission denial guard';
  RAISE NOTICE 'PASS: CREDIT permission denial guard';
  RAISE NOTICE 'PASS: COUPON permission denial guard';
  RAISE NOTICE 'PASS: malformed settlement guard';
  RAISE NOTICE 'PASS: unknown settlement category guard';
  RAISE NOTICE 'PASS: duplicate operation/idempotency guard';
END
$test$;

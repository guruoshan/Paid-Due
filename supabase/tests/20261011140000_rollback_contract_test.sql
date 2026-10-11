-- Read-only rollback-baseline contract check.
-- Run against a disposable database after applying the rollback SQL, or against
-- the current pre-migration database to verify the expected restored behavior.
DO $test$
DECLARE
  v_def text;
BEGIN
  SELECT pg_get_functiondef(
    'public.ingest_cashier_financial_envelope(text,text,text,text,text,text,integer,timestamp with time zone,text,date,text,text,jsonb)'::regprocedure
  ) INTO v_def;

  IF v_def IS NULL THEN
    RAISE EXCEPTION 'FAIL: cashier financial envelope function missing';
  END IF;
  IF position('NOT v_membership.can_accept_cash' IN v_def) > 0
     OR position('NOT v_membership.can_accept_online' IN v_def) > 0
     OR position('NOT v_membership.can_create_credit_sale' IN v_def) > 0
     OR position('NOT v_membership.can_use_coupon' IN v_def) > 0 THEN
    RAISE EXCEPTION 'FAIL: tender guards remain installed; rollback baseline not restored';
  END IF;
  IF position('WHEN ''OPEN_CASH_SHIFT''' IN v_def) > 0
     OR position('WHEN ''COUNT_CASH_SHIFT''' IN v_def) > 0
     OR position('WHEN ''CLOSE_CASH_SHIFT''' IN v_def) > 0 THEN
    RAISE EXCEPTION 'FAIL: shift lifecycle allowlist remains installed; rollback baseline not restored';
  END IF;
  IF position('WHEN ''RECORD_SALE''' IN v_def) = 0
     OR position('THEN v_membership.can_create_sale' IN v_def) = 0 THEN
    RAISE EXCEPTION 'FAIL: expected pre-migration sale authorization baseline not found';
  END IF;

  RAISE NOTICE 'PASS: rollback baseline restores legacy sale authorization';
  RAISE NOTICE 'PASS: rollback baseline removes CASH/ONLINE/CREDIT/COUPON tender guards';
  RAISE NOTICE 'PASS: rollback baseline removes shift lifecycle allowlist';
END
$test$;

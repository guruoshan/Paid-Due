\set ON_ERROR_STOP on
DO $test$
DECLARE
  v_constraint text;
  v_create text;
  v_resolve text;
  v_old text := '''CHANGE_PAYMENT_METHOD'',''VIEW_BROAD_HISTORY'')';
BEGIN
  SELECT pg_get_constraintdef(oid) INTO v_constraint
  FROM pg_constraint
  WHERE conrelid = 'private.cashier_permission_requests'::regclass
    AND conname = 'cashier_permission_requests_permission_key_check';
  IF v_constraint IS NULL
     OR position('ISSUE_RECEIPT' IN v_constraint) = 0
     OR position('MANAGE_PRODUCTS' IN v_constraint) = 0
     OR position('VIEW_REPORTS' IN v_constraint) = 0 THEN
    RAISE EXCEPTION '14-key permission constraint not installed';
  END IF;

  v_create := pg_get_functiondef('public.create_cashier_permission_request(text,text,text)'::regprocedure);
  v_resolve := pg_get_functiondef('public.resolve_admin_permission_request(uuid,text,jsonb)'::regprocedure);
  IF position(v_old IN v_create) > 0 OR position(v_old IN v_resolve) > 0 THEN
    RAISE EXCEPTION 'stale 11-key permission allowlist remains';
  END IF;
  FOREACH v_constraint IN ARRAY ARRAY['ISSUE_RECEIPT','MANAGE_PRODUCTS','VIEW_REPORTS'] LOOP
    IF position(v_constraint IN v_create) = 0 OR position(v_constraint IN v_resolve) = 0 THEN
      RAISE EXCEPTION 'permission key % missing from request/resolve function', v_constraint;
    END IF;
  END LOOP;
END;
$test$;

SELECT 'PASS: permission request constraint and both server functions accept all 14 keys';

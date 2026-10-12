-- Expand cashier permission requests to the complete 14-key API contract.
-- Additive and idempotent: no membership flags, requests, or audit history are rewritten.
ALTER TABLE private.cashier_permission_requests
  DROP CONSTRAINT IF EXISTS cashier_permission_requests_permission_key_check;

ALTER TABLE private.cashier_permission_requests
  ADD CONSTRAINT cashier_permission_requests_permission_key_check
  CHECK (permission_key IN (
    'CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE',
    'USE_COUPON','ISSUE_INVOICE','ISSUE_RECEIPT','REFUND','VOID',
    'CORRECT_AMOUNT','CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY',
    'MANAGE_PRODUCTS','VIEW_REPORTS'
  ));

-- The deployed functions already contain the correct membership/permission mapping.
-- Patch only their stale 11-key allowlists and refuse to silently drift if the
-- expected old contract is not present.
DO $migration$
DECLARE
  v_old text := '''CHANGE_PAYMENT_METHOD'',''VIEW_BROAD_HISTORY'')';
  v_new text := '''CHANGE_PAYMENT_METHOD'',''VIEW_BROAD_HISTORY'',''ISSUE_RECEIPT'',''MANAGE_PRODUCTS'',''VIEW_REPORTS'')';
  v_definition text;
BEGIN
  v_definition := pg_get_functiondef('public.create_cashier_permission_request(text,text,text)'::regprocedure);
  IF position(v_old IN v_definition) = 0 THEN
    RAISE EXCEPTION 'Permission migration refused: create_cashier_permission_request allowlist has unexpected shape';
  END IF;
  v_definition := replace(v_definition, v_old, v_new);
  EXECUTE v_definition;

  v_definition := pg_get_functiondef('public.resolve_admin_permission_request(uuid,text,jsonb)'::regprocedure);
  IF position(v_old IN v_definition) = 0 THEN
    RAISE EXCEPTION 'Permission migration refused: resolve_admin_permission_request allowlist has unexpected shape';
  END IF;
  v_definition := replace(v_definition, v_old, v_new);
  EXECUTE v_definition;
END;
$migration$;

DO $verify$
DECLARE
  v_old text := '''CHANGE_PAYMENT_METHOD'',''VIEW_BROAD_HISTORY''';
  v_constraint text;
  v_request_definition text;
  v_resolve_definition text;
BEGIN
  SELECT pg_get_constraintdef(oid) INTO v_constraint
  FROM pg_constraint
  WHERE conrelid = 'private.cashier_permission_requests'::regclass
    AND conname = 'cashier_permission_requests_permission_key_check';

  IF v_constraint IS NULL
     OR position('ISSUE_RECEIPT' IN v_constraint) = 0
     OR position('MANAGE_PRODUCTS' IN v_constraint) = 0
     OR position('VIEW_REPORTS' IN v_constraint) = 0 THEN
    RAISE EXCEPTION 'Permission migration verification failed: constraint does not include all 14 keys';
  END IF;

  v_request_definition := pg_get_functiondef('public.create_cashier_permission_request(text,text,text)'::regprocedure);
  v_resolve_definition := pg_get_functiondef('public.resolve_admin_permission_request(uuid,text,jsonb)'::regprocedure);

  IF position(v_old IN v_request_definition) > 0
     OR position(v_old IN v_resolve_definition) > 0 THEN
    RAISE EXCEPTION 'Permission migration verification failed: stale 11-key allowlist remains';
  END IF;
  IF position('ISSUE_RECEIPT' IN v_request_definition) = 0
     OR position('MANAGE_PRODUCTS' IN v_request_definition) = 0
     OR position('VIEW_REPORTS' IN v_request_definition) = 0
     OR position('ISSUE_RECEIPT' IN v_resolve_definition) = 0
     OR position('MANAGE_PRODUCTS' IN v_resolve_definition) = 0
     OR position('VIEW_REPORTS' IN v_resolve_definition) = 0 THEN
    RAISE EXCEPTION 'Permission migration verification failed: function allowlists incomplete';
  END IF;
END;
$verify$;

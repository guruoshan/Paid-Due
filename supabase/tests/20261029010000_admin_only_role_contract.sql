-- Disposable/staging contract checks for the final ADMIN-only role boundary.
-- This test is read-only apart from its transaction-scoped catalog assertions.
BEGIN;
DO $test$
DECLARE
  v_active_cashiers bigint;
  v_trigger_count integer;
  v_constraint_count integer;
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

  IF to_regprocedure('public.create_staff_invite_for_admin(text,text,text,text,text,jsonb,uuid)') IS NOT NULL
     OR to_regprocedure('public.activate_staff_invite(text,text,text)') IS NOT NULL
     OR to_regprocedure('public.cashier_login_credential(text)') IS NOT NULL
     OR to_regprocedure('public.create_cashier_sync_session(text,text)') IS NOT NULL
     OR to_regprocedure('public.update_admin_cashier_permissions(text,jsonb)') IS NOT NULL
     OR to_regprocedure('public.update_cashier_permissions(text,jsonb)') IS NOT NULL
     OR to_regprocedure('public.ingest_cashier_financial_envelope(text,text,text,text,text,text,integer,timestamp with time zone,text,date,text,text,jsonb)') IS NOT NULL THEN
    RAISE EXCEPTION 'FAIL: a retired cashier RPC remains installed';
  END IF;

  RAISE NOTICE 'PASS: no active CASHIER memberships';
  RAISE NOTICE 'PASS: new CASHIER membership/role creation blocked by trigger';
  RAISE NOTICE 'PASS: legacy CASHIER rows constrained to inactive permissionless tombstones';
  RAISE NOTICE 'PASS: retired cashier RPCs absent';
END
$test$;
ROLLBACK;

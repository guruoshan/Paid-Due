\set ON_ERROR_STOP on
BEGIN;

DO $test$
DECLARE
  v_admin_uid uuid := 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  v_occurred_at timestamptz := '2026-10-09 10:00:00+00'::timestamptz;
  v_payload jsonb;
  v_bad_payload jsonb;
  v_canonical_text text;
  v_bad_canonical_text text;
  v_digest text;
  v_bad_digest text;
  v_result record;
  v_rejected boolean;
  v_count bigint;
  v_definition text;
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'business_memberships'
      AND column_name = 'can_access_customer_directory'
  ) THEN
    RAISE EXCEPTION 'Unexpected new customer-directory permission column';
  END IF;

  v_definition := pg_catalog.pg_get_functiondef(
    'public.update_admin_cashier_permissions(text,jsonb)'::regprocedure
  );
  IF position('CUSTOMER_DIRECTORY_ACCESS' in v_definition) > 0
     OR position('<> 14 THEN' in v_definition) = 0 THEN
    RAISE EXCEPTION 'Frontend/backend permission schema is not the established 14-key schema';
  END IF;

  v_definition := pg_catalog.pg_get_functiondef(
    'public.sync_customer_directory_list(text,text,text,timestamp with time zone,integer)'::regprocedure
  );
  IF position('can_view_broad_history' in v_definition) = 0 THEN
    RAISE EXCEPTION 'Customer directory list does not enforce the existing permission';
  END IF;

  UPDATE public.businesses SET is_active = true WHERE id = 'biz-test';
  UPDATE public.branches SET is_active = true, timezone_id = 'Asia/Muscat'
    WHERE id = 'branch-test';
  UPDATE public.business_memberships
    SET local_user_id = 'cashier-local', can_view_broad_history = true
    WHERE id = 'membership-test';

  INSERT INTO public.profiles(auth_user_id, display_name, is_active)
  VALUES (v_admin_uid, 'Test Admin', true)
  ON CONFLICT (auth_user_id) DO UPDATE SET is_active = true, display_name = EXCLUDED.display_name;

  INSERT INTO public.business_memberships(
    id, business_id, branch_id, auth_user_id, role, is_active, removed_at, staff_identity_id
  ) VALUES (
    'admin-membership-test', 'biz-test', 'branch-test', v_admin_uid,
    'ADMIN', true, NULL, NULL
  )
  ON CONFLICT (id) DO UPDATE SET
    is_active = true, removed_at = NULL, branch_id = EXCLUDED.branch_id;

  PERFORM pg_catalog.set_config('request.jwt.claim.sub', v_admin_uid::text, true);
  IF NOT private.is_branch_admin('branch-test')
     OR NOT private.is_business_admin('biz-test')
     OR NOT private.is_branch_member('branch-test')
     OR NOT private.is_business_member('biz-test') THEN
    RAISE EXCEPTION 'Active Admin membership must pass active tenant/branch helper checks';
  END IF;

  UPDATE public.business_memberships SET removed_at = now()
    WHERE id = 'admin-membership-test';
  IF private.is_branch_admin('branch-test')
     OR private.is_business_admin('biz-test')
     OR private.is_branch_member('branch-test')
     OR private.is_business_member('biz-test') THEN
    RAISE EXCEPTION 'Removed Admin membership must fail helper checks';
  END IF;
  UPDATE public.business_memberships SET removed_at = NULL
    WHERE id = 'admin-membership-test';

  DELETE FROM private.cashier_sync_sessions WHERE token_hash = repeat('a', 64);
  INSERT INTO private.cashier_sync_sessions(membership_id, token_hash, expires_at, revoked_at)
  VALUES ('membership-test', repeat('a', 64), now() + interval '1 hour', NULL);
  SELECT count(*) INTO v_count
  FROM public.verify_cashier_notification_session(repeat('a', 64));
  IF v_count <> 1 THEN
    RAISE EXCEPTION 'Active cashier notification session must verify';
  END IF;
  UPDATE private.cashier_sync_sessions SET revoked_at = now()
    WHERE token_hash = repeat('a', 64);
  SELECT count(*) INTO v_count
  FROM public.verify_cashier_notification_session(repeat('a', 64));
  IF v_count <> 0 THEN
    RAISE EXCEPTION 'Revoked cashier notification session must be rejected';
  END IF;

  -- Existing VIEW_BROAD_HISTORY is the permission boundary; no 15th key is added.
  SELECT count(*) INTO v_count
  FROM public.sync_customer_directory_list('biz-test', 'branch-test', 'membership-test', NULL, 100);
  UPDATE public.business_memberships SET can_view_broad_history = false
    WHERE id = 'membership-test';
  v_rejected := false;
  BEGIN
    PERFORM 1
    FROM public.sync_customer_directory_list('biz-test', 'branch-test', 'membership-test', NULL, 100);
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'Cashier without VIEW_BROAD_HISTORY was allowed to access customer directory';
  END IF;
  UPDATE public.business_memberships SET can_view_broad_history = true
    WHERE id = 'membership-test';

  v_payload := pg_catalog.jsonb_build_object(
    'operation_id', 'release-blocker-replay-test',
    'business_id', 'biz-test',
    'branch_id', 'branch-test',
    'operation_type', 'RECORD_SALE',
    'actor_user_id', 'cashier-local',
    'command_schema_version', 1,
    'occurred_at', floor(extract(epoch FROM v_occurred_at) * 1000)::bigint,
    'request_fingerprint', 'release-blocker-fingerprint',
    'settlements', jsonb_build_array(jsonb_build_object('category', 'CASH'))
  );
  v_canonical_text := v_payload::text;
  v_digest := encode(extensions.digest(convert_to(v_canonical_text, 'UTF8'), 'sha256'), 'hex');

  SELECT * INTO v_result
  FROM public.ingest_cashier_financial_envelope(
    'biz-test', 'release-blocker-replay-test', 'branch-test', 'membership-test',
    'release-blocker-device', 'RECORD_SALE', 1, v_occurred_at, 'Asia/Muscat',
    (v_occurred_at AT TIME ZONE 'Asia/Muscat')::date,
    v_canonical_text, v_digest, v_payload
  );
  IF v_result.status <> 'ACKNOWLEDGED' OR v_result.duplicate THEN
    RAISE EXCEPTION 'First valid envelope was not acknowledged as new';
  END IF;

  SELECT * INTO v_result
  FROM public.ingest_cashier_financial_envelope(
    'biz-test', 'release-blocker-replay-test', 'branch-test', 'membership-test',
    'release-blocker-device', 'RECORD_SALE', 1, v_occurred_at, 'Asia/Muscat',
    (v_occurred_at AT TIME ZONE 'Asia/Muscat')::date,
    v_canonical_text, v_digest, v_payload
  );
  IF v_result.status <> 'ACKNOWLEDGED' OR NOT v_result.duplicate THEN
    RAISE EXCEPTION 'Identical operation replay was not acknowledged as duplicate';
  END IF;

  SELECT count(*) INTO v_count
  FROM private.financial_operation_envelopes
  WHERE business_id = 'biz-test'
    AND operation_id = 'release-blocker-replay-test';
  IF v_count <> 1 THEN
    RAISE EXCEPTION 'Replay must persist exactly one financial envelope';
  END IF;

  v_bad_payload := v_payload || jsonb_build_object('operation_id', 'release-blocker-bad-date-test');
  v_bad_canonical_text := v_bad_payload::text;
  v_bad_digest := encode(extensions.digest(convert_to(v_bad_canonical_text, 'UTF8'), 'sha256'), 'hex');
  v_rejected := false;
  BEGIN
    PERFORM *
    FROM public.ingest_cashier_financial_envelope(
      'biz-test', 'release-blocker-bad-date-test', 'branch-test', 'membership-test',
      'release-blocker-device', 'RECORD_SALE', 1, v_occurred_at, 'Asia/Muscat',
      date '2026-10-08',
      v_bad_canonical_text, v_bad_digest, v_bad_payload
    );
  EXCEPTION WHEN OTHERS THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN
    RAISE EXCEPTION 'Mismatched business-local date was accepted';
  END IF;

  v_definition := pg_catalog.pg_get_functiondef(
    'private.notify_admins_of_confirmed_day_close()'::regprocedure
  );
  IF position('EXCEPTION WHEN OTHERS' in upper(v_definition)) > 0 THEN
    RAISE EXCEPTION 'Day-close notification must not swallow failures';
  END IF;
  v_definition := pg_catalog.pg_get_functiondef(
    'private.notify_cashier_of_permission_change()'::regprocedure
  );
  IF position('EXCEPTION WHEN OTHERS' in upper(v_definition)) > 0 THEN
    RAISE EXCEPTION 'Permission notification must not swallow failures';
  END IF;
END;
$test$;

ROLLBACK;
\echo PASS: release-blocker SQL security, permission, session-revocation, and envelope replay tests

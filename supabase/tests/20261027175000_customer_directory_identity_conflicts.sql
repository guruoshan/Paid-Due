\set ON_ERROR_STOP on
-- Regression coverage for the active Admin-only customer-directory RPC.
INSERT INTO public.businesses(id) VALUES ('business-a')
ON CONFLICT (id) DO NOTHING;
INSERT INTO public.branches(id,business_id) VALUES ('branch-a','business-a')
ON CONFLICT (id) DO NOTHING;
INSERT INTO public.profiles(auth_user_id,is_active,display_name)
VALUES ('00000000-0000-4000-8000-000000000101',true,'Test Admin')
ON CONFLICT (auth_user_id) DO UPDATE SET is_active=true;
INSERT INTO public.business_memberships(id,business_id,branch_id,role,auth_user_id,staff_identity_id)
VALUES ('admin-a','business-a','branch-a','ADMIN','00000000-0000-4000-8000-000000000101',NULL)
ON CONFLICT (id) DO UPDATE SET is_active=true, removed_at=NULL, role='ADMIN',
  auth_user_id='00000000-0000-4000-8000-000000000101', staff_identity_id=NULL;
SELECT set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000000101',false);

DO $test$
DECLARE
  v_first record;
  v_retry record;
  v_repeated record;
  v_conflict boolean;
  v_count integer;
BEGIN
  -- Same phone + same customer ID: create once.
  SELECT * INTO v_first FROM public.sync_customer_directory_admin_upsert(
    'business-a','branch-a','identity-a','+96890000201','Customer A','2026-01-01T00:00:00Z'
  );
  IF v_first.customer_id <> 'identity-a' OR NOT v_first.was_created THEN
    RAISE EXCEPTION 'same phone/same ID initial insert failed';
  END IF;

  -- Same phone + same ID on replay: return the existing identity without mutation.
  SELECT * INTO v_retry FROM public.sync_customer_directory_admin_upsert(
    'business-a','branch-a','identity-a','+96890000201','Customer A','2026-01-01T00:00:00Z'
  );
  IF v_retry.customer_id <> 'identity-a' OR v_retry.was_created OR
     v_retry.display_name <> 'Customer A' OR v_retry.created_at <> v_first.created_at THEN
    RAISE EXCEPTION 'identical replay was not idempotent';
  END IF;

  -- Repeated sync payload: must preserve one row and the original canonical ID.
  SELECT * INTO v_repeated FROM public.sync_customer_directory_admin_upsert(
    'business-a','branch-a','identity-a','+96890000201','Changed by retry','2026-01-01T00:00:00Z'
  );
  IF v_repeated.customer_id <> 'identity-a' OR v_repeated.was_created OR
     v_repeated.display_name <> 'Customer A' THEN
    RAISE EXCEPTION 'repeated sync payload changed canonical record';
  END IF;

  -- Same phone + different ID must fail explicitly, not return identity-a.
  v_conflict := false;
  BEGIN
    PERFORM * FROM public.sync_customer_directory_admin_upsert(
      'business-a','branch-a','identity-b','+96890000201','Conflicting Customer','2026-01-01T00:00:00Z'
    );
  EXCEPTION WHEN unique_violation THEN
    v_conflict := true;
  END;
  IF NOT v_conflict THEN
    RAISE EXCEPTION 'same phone/different ID was silently accepted';
  END IF;

  -- Different phone + same ID must also fail explicitly.
  v_conflict := false;
  BEGIN
    PERFORM * FROM public.sync_customer_directory_admin_upsert(
      'business-a','branch-a','identity-a','+96890000202','Different Phone','2026-01-01T00:00:00Z'
    );
  EXCEPTION WHEN unique_violation THEN
    v_conflict := true;
  END;
  IF NOT v_conflict THEN
    RAISE EXCEPTION 'same ID/different phone was silently accepted';
  END IF;

  SELECT count(*) INTO v_count FROM private.customer_directory
  WHERE business_id='business-a' AND canonical_phone IN ('+96890000201','+96890000202');
  IF v_count <> 1 THEN
    RAISE EXCEPTION 'conflict path changed customer row count: %', v_count;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM private.customer_directory
    WHERE business_id='business-a' AND canonical_phone='+96890000201'
      AND customer_id='identity-a' AND display_name='Customer A'
  ) THEN
    RAISE EXCEPTION 'canonical record changed after conflict attempts';
  END IF;
END;
$test$;

SELECT 'PASS: customer identity conflicts reject; same-identity replay remains idempotent';

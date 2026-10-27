\set ON_ERROR_STOP on
-- PD-01 keyset pagination regression tests. Run only in a disposable local test database.
INSERT INTO public.businesses(id) VALUES ('pagination-business-a')
ON CONFLICT (id) DO NOTHING;
INSERT INTO public.branches(id,business_id) VALUES ('pagination-branch-a','pagination-business-a')
ON CONFLICT (id) DO NOTHING;
INSERT INTO public.profiles(auth_user_id,is_active,display_name)
VALUES ('00000000-0000-4000-8000-000000000102',true,'Pagination Test Admin')
ON CONFLICT (auth_user_id) DO UPDATE SET is_active=true;
INSERT INTO public.business_memberships(id,business_id,branch_id,role,auth_user_id,staff_identity_id)
VALUES ('pagination-admin','pagination-business-a','pagination-branch-a','ADMIN',
  '00000000-0000-4000-8000-000000000102',NULL)
ON CONFLICT (id) DO UPDATE SET is_active=true, removed_at=NULL, role='ADMIN',
  auth_user_id='00000000-0000-4000-8000-000000000102', staff_identity_id=NULL;
SELECT set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000000102',false);

-- Stable fixture with 1,201 records sharing exactly the same timestamp.
DELETE FROM private.customer_directory WHERE business_id='pagination-business-a';
INSERT INTO private.customer_directory(
  business_id,canonical_phone,customer_id,display_name,created_at,updated_at,
  created_by_membership_id,updated_by_membership_id
)
SELECT 'pagination-business-a',
       '+9689' || lpad(i::text,7,'0'),
       'pagination-customer-' || i::text,
       'Pagination customer ' || i::text,
       '2026-10-01T00:00:00Z'::timestamptz,
       '2026-10-01T00:00:00Z'::timestamptz,
       'pagination-admin','pagination-admin'
FROM generate_series(1,1201) AS i;

DO $test$
DECLARE
  v_page record;
  v_first_page text[];
  v_replay_page text[];
  v_after_updated_at timestamptz := NULL;
  v_after_phone text := NULL;
  v_page_count integer;
  v_total integer := 0;
  v_pages integer := 0;
  v_unique integer;
  v_empty integer;
BEGIN
  -- Replay the same first-page request and compare every returned identity.
  SELECT array_agg(customer_id ORDER BY updated_at, canonical_phone) INTO v_first_page
  FROM public.sync_customer_directory_admin_list(
    'pagination-business-a','pagination-branch-a',NULL,NULL,500
  );
  SELECT array_agg(customer_id ORDER BY updated_at, canonical_phone) INTO v_replay_page
  FROM public.sync_customer_directory_admin_list(
    'pagination-business-a','pagination-branch-a',NULL,NULL,500
  );
  IF cardinality(v_first_page) <> 500 OR v_replay_page IS DISTINCT FROM v_first_page THEN
    RAISE EXCEPTION 'replay did not return the same complete first page';
  END IF;

  -- Save the first page's final tuple, then insert a row that sorts before it
  -- while pagination is in progress. It must not duplicate or shift later pages;
  -- a fresh traversal must pick it up on the next sync pass.
  SELECT updated_at, canonical_phone INTO v_after_updated_at, v_after_phone
  FROM public.sync_customer_directory_admin_list(
    'pagination-business-a','pagination-branch-a',NULL,NULL,500
  )
  ORDER BY updated_at DESC, canonical_phone DESC
  LIMIT 1;
  INSERT INTO private.customer_directory(
    business_id,canonical_phone,customer_id,display_name,created_at,updated_at,
    created_by_membership_id,updated_by_membership_id
  ) VALUES (
    'pagination-business-a','+96880000000','pagination-concurrent-before-cursor',
    'Concurrent earlier key','2026-10-01T00:00:00Z','2026-10-01T00:00:00Z',
    'pagination-admin','pagination-admin'
  );

  -- Count the first page, then walk the remaining pages with the strict tuple.
  v_total := cardinality(v_first_page);
  v_pages := 1;
  LOOP
    v_page_count := 0;
    FOR v_page IN SELECT * FROM public.sync_customer_directory_admin_list(
      'pagination-business-a','pagination-branch-a',v_after_updated_at,v_after_phone,500
    ) LOOP
      v_page_count := v_page_count + 1;
      IF (v_page.updated_at, v_page.canonical_phone) <= (v_after_updated_at, v_after_phone) THEN
        RAISE EXCEPTION 'keyset cursor did not advance strictly';
      END IF;
      v_after_updated_at := v_page.updated_at;
      v_after_phone := v_page.canonical_phone;
    END LOOP;
    EXIT WHEN v_page_count = 0;
    v_total := v_total + v_page_count;
    v_pages := v_pages + 1;
  END LOOP;

  SELECT count(DISTINCT customer_id) INTO v_unique
  FROM private.customer_directory
  WHERE business_id='pagination-business-a'
    AND customer_id <> 'pagination-concurrent-before-cursor';
  IF v_total <> 1201 OR v_unique <> 1201 OR v_pages <> 3 THEN
    RAISE EXCEPTION 'multi-page traversal mismatch: total %, unique %, pages %', v_total, v_unique, v_pages;
  END IF;

  -- Empty final page is the explicit termination signal.
  SELECT count(*) INTO v_empty FROM public.sync_customer_directory_admin_list(
    'pagination-business-a','pagination-branch-a',v_after_updated_at,v_after_phone,500
  );
  IF v_empty <> 0 THEN RAISE EXCEPTION 'final cursor did not return an empty page'; END IF;

  -- The concurrent earlier-key row is outside this pass's cursor but visible on
  -- the next full sync from NULL; this is not a cross-request snapshot guarantee.
  SELECT count(*) INTO v_empty FROM public.sync_customer_directory_admin_list(
    'pagination-business-a','pagination-branch-a',NULL,NULL,500
  );
  IF v_empty <> 500 THEN RAISE EXCEPTION 'fresh traversal did not restart at first page'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.sync_customer_directory_admin_list(
      'pagination-business-a','pagination-branch-a',NULL,NULL,500
    ) WHERE customer_id='pagination-concurrent-before-cursor'
  ) THEN
    RAISE EXCEPTION 'fresh traversal did not pick up concurrent insert';
  END IF;
END;
$test$;

SELECT 'PASS: timestamp ties, boundary collisions, 1,201-row traversal, empty final page, replay, and concurrent-insert semantics';

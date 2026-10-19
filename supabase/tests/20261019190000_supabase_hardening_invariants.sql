-- Supabase hardening invariant checks. Run after migrations against a disposable
-- Supabase/Postgres database. Read-only: does not create fixture data or mutate rows.
DO $hardening$
DECLARE
  v_bad_count bigint;
BEGIN
  -- All application tables in exposed/private application schemas must keep RLS enabled.
  SELECT count(*) INTO v_bad_count
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE c.relkind = 'r'
    AND n.nspname IN ('public', 'private')
    AND NOT c.relrowsecurity;
  IF v_bad_count <> 0 THEN
    RAISE EXCEPTION 'RLS invariant failed: % application tables have RLS disabled', v_bad_count;
  END IF;

  -- A business/operation pair must remain globally unique, even under replay.
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid = 'private.financial_operation_envelopes'::regclass
      AND conname = 'financial_operation_envelopes_pkey'
      AND contype = 'p'
  ) THEN
    RAISE EXCEPTION 'Idempotency invariant failed: envelope primary key missing';
  END IF;

  SELECT count(*) INTO v_bad_count
  FROM (
    SELECT business_id, operation_id
    FROM private.financial_operation_envelopes
    GROUP BY business_id, operation_id
    HAVING count(*) > 1
  ) duplicates;
  IF v_bad_count <> 0 THEN
    RAISE EXCEPTION 'Idempotency invariant failed: % duplicate envelope keys', v_bad_count;
  END IF;

  -- Existing financial rows must retain canonical JSON and cryptographic digest integrity.
  SELECT count(*) INTO v_bad_count
  FROM private.financial_operation_envelopes e
  WHERE e.canonical_payload IS DISTINCT FROM e.canonical_text::jsonb
     OR e.canonical_sha256 IS DISTINCT FROM encode(
          extensions.digest(convert_to(e.canonical_text, 'UTF8'), 'sha256'), 'hex')
     OR e.canonical_payload ->> 'operation_id' IS DISTINCT FROM e.operation_id
     OR e.canonical_payload ->> 'business_id' IS DISTINCT FROM e.business_id;
  IF v_bad_count <> 0 THEN
    RAISE EXCEPTION 'Financial integrity invariant failed: % invalid envelopes', v_bad_count;
  END IF;

  -- Branch and actor membership references must never cross the envelope's tenant scope.
  SELECT count(*) INTO v_bad_count
  FROM private.financial_operation_envelopes e
  LEFT JOIN public.branches b
    ON b.id = e.branch_id AND b.business_id = e.business_id
  LEFT JOIN public.business_memberships m
    ON m.id = e.actor_membership_id
   AND m.business_id = e.business_id
   AND m.branch_id = e.branch_id
  WHERE b.id IS NULL OR m.id IS NULL;
  IF v_bad_count <> 0 THEN
    RAISE EXCEPTION 'Tenant isolation invariant failed: % envelopes reference mismatched branch/actor scope', v_bad_count;
  END IF;

  -- The canonical ingest endpoint must keep a transactionally enforced conflict path
  -- and a SECURITY DEFINER search_path hardening setting.
  IF NOT EXISTS (
    SELECT 1
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.proname = 'ingest_financial_envelope'
      AND p.prosecdef
      AND coalesce(array_to_string(p.proconfig, ','), '') LIKE '%search_path=%'
      AND pg_get_functiondef(p.oid) ILIKE '%ON CONFLICT ON CONSTRAINT financial_operation_envelopes_pkey DO NOTHING%'
      AND pg_get_functiondef(p.oid) ILIKE '%OPERATION_COLLISION%'
  ) THEN
    RAISE EXCEPTION 'Ingestion hardening invariant failed: secure idempotent ingest contract missing';
  END IF;

  -- The notification policy keeps its authorization predicates while avoiding
  -- per-row auth.uid() evaluation.
  IF NOT EXISTS (
    SELECT 1
    FROM pg_policies
    WHERE schemaname = 'private'
      AND tablename = 'business_notifications'
      AND policyname = 'admin_read_own_business_notifications'
      AND qual ILIKE '%( SELECT auth.uid() AS uid)%'
      AND qual ILIKE '%m.role = ''ADMIN''%'
      AND qual ILIKE '%m.removed_at IS NULL%'
  ) THEN
    RAISE EXCEPTION 'RLS policy invariant failed: optimized admin notification policy missing';
  END IF;

  RAISE NOTICE 'Supabase hardening invariants passed: RLS enabled, tenant references aligned, canonical digests valid, duplicate keys absent, idempotent ingest contract present, notification RLS policy preserved.';
END
$hardening$;

-- Scoped, read-only cloud inventory for identity-based financial report reconciliation.
-- This function returns accepted envelopes only; it never mutates financial data.
CREATE OR REPLACE FUNCTION public.fetch_financial_reporting_inventory_v1(
    p_business_id text,
    p_branch_ids text[],
    p_from_occurred_at timestamptz,
    p_to_occurred_at timestamptz,
    p_actor_user_id text DEFAULT NULL,
    p_after_received_at timestamptz DEFAULT NULL,
    p_after_operation_id text DEFAULT NULL,
    p_limit integer DEFAULT 100
)
RETURNS TABLE (
    operation_id text,
    business_id text,
    branch_id text,
    operation_type text,
    occurred_at timestamptz,
    received_at timestamptz,
    canonical_text text,
    canonical_payload jsonb,
    canonical_sha256 text
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
    v_uid uuid := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Authentication required' USING errcode = '42501';
    END IF;

    IF p_business_id IS NULL OR btrim(p_business_id) = ''
       OR p_branch_ids IS NULL OR cardinality(p_branch_ids) = 0
       OR p_from_occurred_at IS NULL OR p_to_occurred_at IS NULL
       OR p_from_occurred_at >= p_to_occurred_at THEN
        RAISE EXCEPTION 'Invalid reporting inventory scope' USING errcode = '22023';
    END IF;

    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 100 THEN
        RAISE EXCEPTION 'Invalid page limit' USING errcode = '22023';
    END IF;

    IF (p_after_received_at IS NULL) <> (p_after_operation_id IS NULL) THEN
        RAISE EXCEPTION 'Invalid inventory cursor' USING errcode = '22023';
    END IF;

    IF cardinality(p_branch_ids) <> (
        SELECT count(DISTINCT requested.branch_id) FROM unnest(p_branch_ids) AS requested(branch_id)
    ) THEN
        RAISE EXCEPTION 'Duplicate branch IDs in reporting scope' USING errcode = '22023';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM unnest(p_branch_ids) AS requested(branch_id)
        WHERE NOT EXISTS (
            SELECT 1
            FROM public.business_memberships m
            JOIN public.businesses b
              ON b.id = m.business_id
             AND b.is_active = true
            JOIN public.branches br
              ON br.id = m.branch_id
             AND br.business_id = m.business_id
             AND br.is_active = true
            WHERE m.auth_user_id = v_uid
              AND m.business_id = p_business_id
              AND m.branch_id = requested.branch_id
              AND m.role = 'ADMIN'
              AND m.is_active = true
              AND m.removed_at IS NULL
              AND m.staff_identity_id IS NULL
        )
    ) THEN
        RAISE EXCEPTION 'Admin authorization required for every requested branch'
            USING errcode = '42501';
    END IF;

    RETURN QUERY
    SELECT e.operation_id,
           e.business_id,
           e.branch_id,
           e.operation_type,
           e.occurred_at,
           e.received_at,
           e.canonical_text,
           e.canonical_payload,
           e.canonical_sha256
    FROM private.financial_operation_envelopes e
    WHERE e.business_id = p_business_id
      AND e.branch_id = ANY(p_branch_ids)
      AND e.occurred_at >= p_from_occurred_at
      AND e.occurred_at < p_to_occurred_at
      AND (
          p_actor_user_id IS NULL
          OR e.canonical_payload ->> 'actor_user_id' = p_actor_user_id
      )
      AND (
          p_after_received_at IS NULL
          OR (e.received_at, e.operation_id) > (p_after_received_at, p_after_operation_id)
      )
    ORDER BY e.received_at, e.operation_id
    LIMIT p_limit;
END;
$function$;

REVOKE ALL ON FUNCTION public.fetch_financial_reporting_inventory_v1(
    text, text[], timestamptz, timestamptz, text, timestamptz, text, integer
) FROM PUBLIC, anon, authenticated, service_role;

GRANT EXECUTE ON FUNCTION public.fetch_financial_reporting_inventory_v1(
    text, text[], timestamptz, timestamptz, text, timestamptz, text, integer
) TO authenticated;

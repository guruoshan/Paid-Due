CREATE OR REPLACE FUNCTION public.fetch_financial_envelopes(
 p_business_id text,
 p_since_received_at timestamptz DEFAULT '1970-01-01T00:00:00Z'::timestamptz,
 p_limit integer DEFAULT 100
)
RETURNS TABLE(operation_id text,business_id text,branch_id text,operation_type text,occurred_at timestamptz,received_at timestamptz,canonical_text text,canonical_payload jsonb)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $$
DECLARE v_uid uuid := (SELECT auth.uid());
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE='42501'; END IF;
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 THEN RAISE EXCEPTION 'Invalid limit' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS (
   SELECT 1 FROM public.business_memberships m
   JOIN public.businesses b ON b.id=m.business_id AND b.is_active
   JOIN public.branches br ON br.id=m.branch_id AND br.business_id=m.business_id AND br.is_active
   JOIN public.profiles p ON p.auth_user_id=m.auth_user_id AND p.is_active
   WHERE m.auth_user_id=v_uid AND m.role='ADMIN' AND m.is_active
     AND m.staff_identity_id IS NULL AND m.business_id=p_business_id
 ) THEN RAISE EXCEPTION 'Admin authorization required' USING ERRCODE='42501'; END IF;
 RETURN QUERY
 SELECT e.operation_id,e.business_id,e.branch_id,e.operation_type,e.occurred_at,e.received_at,e.canonical_text,e.canonical_payload
 FROM private.financial_operation_envelopes e
 WHERE e.business_id=p_business_id
 AND e.received_at > p_since_received_at
 AND EXISTS (
   SELECT 1 FROM public.business_memberships m
   WHERE m.auth_user_id=v_uid AND m.business_id=e.business_id AND m.branch_id=e.branch_id
   AND m.role='ADMIN' AND m.is_active AND m.staff_identity_id IS NULL
 )
 ORDER BY e.received_at,e.operation_id LIMIT p_limit;
END $$;
REVOKE ALL ON FUNCTION public.fetch_financial_envelopes(text,timestamptz,integer) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.fetch_financial_envelopes(text,timestamptz,integer) TO authenticated;

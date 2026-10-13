-- Credential-free, branch-scoped staff identity synchronization and envelope attribution.
-- No financial envelope or historical actor/membership IDs are rewritten.

CREATE OR REPLACE FUNCTION public.list_admin_sync_identities(
  p_business_id text,
  p_branch_id text
)
RETURNS TABLE(
  local_user_id text,
  membership_id text,
  business_id text,
  branch_id text,
  role text,
  permissions jsonb,
  is_active boolean,
  is_removed boolean,
  updated_at timestamptz
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;

  IF p_business_id IS NULL OR btrim(p_business_id) = ''
     OR p_branch_id IS NULL OR btrim(p_branch_id) = '' THEN
    RAISE EXCEPTION 'Business and branch scope are required' USING ERRCODE = '22023';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.business_memberships admin_membership
    JOIN public.profiles admin_profile
      ON admin_profile.auth_user_id = admin_membership.auth_user_id
     AND admin_profile.is_active = true
    JOIN public.businesses b
      ON b.id = admin_membership.business_id AND b.is_active = true
    JOIN public.branches br
      ON br.id = admin_membership.branch_id
     AND br.business_id = admin_membership.business_id
     AND br.is_active = true
    WHERE admin_membership.auth_user_id = v_uid
      AND admin_membership.business_id = p_business_id
      AND admin_membership.branch_id = p_branch_id
      AND admin_membership.role = 'ADMIN'
      AND admin_membership.is_active = true
      AND admin_membership.removed_at IS NULL
      AND admin_membership.staff_identity_id IS NULL
  ) THEN
    RAISE EXCEPTION 'Active Admin membership required for this branch'
      USING ERRCODE = '42501';
  END IF;

  RETURN QUERY
  SELECT
    m.local_user_id,
    m.id,
    m.business_id,
    m.branch_id,
    m.role,
    CASE WHEN m.role = 'CASHIER' THEN
      pg_catalog.jsonb_build_object(
        'CREATE_SALE', m.can_create_sale,
        'ACCEPT_CASH', m.can_accept_cash,
        'ACCEPT_ONLINE', m.can_accept_online,
        'CREATE_CREDIT_SALE', m.can_create_credit_sale,
        'USE_COUPON', m.can_use_coupon,
        'ISSUE_INVOICE', m.can_issue_invoice,
        'ISSUE_RECEIPT', m.can_issue_receipt,
        'REFUND', m.can_refund,
        'VOID', m.can_void,
        'CORRECT_AMOUNT', m.can_correct_amount,
        'CHANGE_PAYMENT_METHOD', m.can_change_payment_method,
        'VIEW_BROAD_HISTORY', m.can_view_broad_history,
        'MANAGE_PRODUCTS', m.can_manage_products,
        'VIEW_REPORTS', m.can_view_reports
      )
    ELSE '{}'::jsonb END,
    (
      m.is_active
      AND m.removed_at IS NULL
      AND b.is_active
      AND br.is_active
      AND pg_catalog.coalesce(si.is_active, true)
    ),
    (m.removed_at IS NOT NULL),
    pg_catalog.coalesce(m.updated_at, m.created_at, pg_catalog.now())
  FROM public.business_memberships m
  JOIN public.businesses b ON b.id = m.business_id
  JOIN public.branches br
    ON br.id = m.branch_id AND br.business_id = m.business_id
  LEFT JOIN private.staff_identities si ON si.id = m.staff_identity_id
  WHERE m.business_id = p_business_id
    AND m.branch_id = p_branch_id
    AND m.local_user_id IS NOT NULL
    AND pg_catalog.btrim(m.local_user_id) <> ''
  ORDER BY m.id;
END;
$function$;

REVOKE ALL ON FUNCTION public.list_admin_sync_identities(text, text)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.list_admin_sync_identities(text, text)
  TO authenticated;

CREATE OR REPLACE FUNCTION public.fetch_financial_envelopes_v4(
  p_business_id text,
  p_after_received_at timestamptz DEFAULT NULL,
  p_after_operation_id text DEFAULT NULL,
  p_limit integer DEFAULT 100
)
RETURNS TABLE(
  operation_id text,
  business_id text,
  branch_id text,
  actor_membership_id text,
  operation_type text,
  occurred_at timestamptz,
  received_at timestamptz,
  canonical_text text,
  canonical_payload jsonb,
  canonical_sha256 text
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;
  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 100 THEN
    RAISE EXCEPTION 'Invalid page limit' USING ERRCODE = '22023';
  END IF;
  IF (p_after_received_at IS NULL) <> (p_after_operation_id IS NULL) THEN
    RAISE EXCEPTION 'Invalid cursor' USING ERRCODE = '22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM public.business_memberships m
    JOIN public.businesses b ON b.id = m.business_id AND b.is_active = true
    JOIN public.branches br
      ON br.id = m.branch_id AND br.business_id = m.business_id AND br.is_active = true
    JOIN public.profiles p ON p.auth_user_id = m.auth_user_id AND p.is_active = true
    WHERE m.auth_user_id = v_uid
      AND m.role = 'ADMIN'
      AND m.is_active = true
      AND m.removed_at IS NULL
      AND m.staff_identity_id IS NULL
      AND m.business_id = p_business_id
  ) THEN
    RAISE EXCEPTION 'Admin authorization required' USING ERRCODE = '42501';
  END IF;

  RETURN QUERY
  SELECT e.operation_id, e.business_id, e.branch_id, e.actor_membership_id,
         e.operation_type, e.occurred_at, e.received_at, e.canonical_text,
         e.canonical_payload, e.canonical_sha256
  FROM private.financial_operation_envelopes e
  WHERE e.business_id = p_business_id
    AND (
      p_after_received_at IS NULL
      OR (e.received_at, e.operation_id) > (p_after_received_at, p_after_operation_id)
    )
    AND EXISTS (
      SELECT 1 FROM public.business_memberships m
      WHERE m.auth_user_id = v_uid
        AND m.business_id = e.business_id
        AND m.branch_id = e.branch_id
        AND m.role = 'ADMIN'
        AND m.is_active = true
        AND m.removed_at IS NULL
        AND m.staff_identity_id IS NULL
    )
  ORDER BY e.received_at, e.operation_id
  LIMIT p_limit;
END;
$function$;

REVOKE ALL ON FUNCTION public.fetch_financial_envelopes_v4(text, timestamptz, text, integer)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fetch_financial_envelopes_v4(text, timestamptz, text, integer)
  TO authenticated;

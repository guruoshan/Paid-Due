-- Expose a persistent removal marker to Admin clients so a removed cloud cashier
-- does not reappear through the local Room fallback after an online refresh.

DO $block$
DECLARE
  v_signature text;
  v_definition text;
  v_old text := 'AND cashier.auth_user_id IS NULL';
  v_new text := 'AND true';
BEGIN
  FOREACH v_signature IN ARRAY ARRAY[
    'public.update_admin_cashier_permissions(text,jsonb)',
    'public.remove_admin_cashier(text)'
  ]
  LOOP
    SELECT pg_catalog.pg_get_functiondef(v_signature::regprocedure)
      INTO v_definition;

    IF length(v_definition) - length(replace(v_definition, v_old, '')) <> length(v_old) THEN
      RAISE EXCEPTION 'Expected cashier auth filter not found exactly once in %', v_signature;
    END IF;

    v_definition := replace(v_definition, v_old, v_new);
    EXECUTE v_definition;
  END LOOP;
END;
$block$;

DROP FUNCTION public.list_admin_cashiers_with_permissions();

CREATE FUNCTION public.list_admin_cashiers_with_permissions()
RETURNS TABLE(
  local_user_id text,
  membership_id text,
  branch_id text,
  display_name text,
  canonical_phone text,
  is_active boolean,
  permissions jsonb,
  is_removed boolean
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_auth_user_id uuid := (SELECT auth.uid());
BEGIN
  IF v_auth_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;

  RETURN QUERY
  SELECT
    cashier.local_user_id,
    cashier.id,
    cashier.branch_id,
    staff.display_name,
    staff.canonical_phone,
    (cashier.is_active AND staff.is_active AND cashier.removed_at IS NULL),
    pg_catalog.jsonb_build_object(
      'CREATE_SALE', cashier.can_create_sale,
      'ACCEPT_CASH', cashier.can_accept_cash,
      'ACCEPT_ONLINE', cashier.can_accept_online,
      'CREATE_CREDIT_SALE', cashier.can_create_credit_sale,
      'USE_COUPON', cashier.can_use_coupon,
      'ISSUE_INVOICE', cashier.can_issue_invoice,
      'ISSUE_RECEIPT', cashier.can_issue_receipt,
      'REFUND', cashier.can_refund,
      'VOID', cashier.can_void,
      'CORRECT_AMOUNT', cashier.can_correct_amount,
      'CHANGE_PAYMENT_METHOD', cashier.can_change_payment_method,
      'VIEW_BROAD_HISTORY', cashier.can_view_broad_history,
      'MANAGE_PRODUCTS', cashier.can_manage_products,
      'VIEW_REPORTS', cashier.can_view_reports
    ),
    (cashier.removed_at IS NOT NULL)
  FROM public.business_memberships AS admin_membership
  JOIN public.profiles AS admin_profile
    ON admin_profile.auth_user_id = admin_membership.auth_user_id
   AND admin_profile.is_active = true
  JOIN public.businesses AS business
    ON business.id = admin_membership.business_id
   AND business.is_active = true
  JOIN public.branches AS branch
    ON branch.id = admin_membership.branch_id
   AND branch.business_id = admin_membership.business_id
   AND branch.is_active = true
  JOIN public.business_memberships AS cashier
    ON cashier.business_id = admin_membership.business_id
   AND cashier.branch_id = admin_membership.branch_id
   AND cashier.role = 'CASHIER'
  JOIN private.staff_identities AS staff
    ON staff.id = cashier.staff_identity_id
  WHERE admin_membership.auth_user_id = v_auth_user_id
    AND admin_membership.role = 'ADMIN'
    AND admin_membership.is_active = true
    AND admin_membership.removed_at IS NULL
    AND admin_membership.staff_identity_id IS NULL
    AND cashier.staff_identity_id IS NOT NULL
    AND cashier.local_user_id IS NOT NULL
    AND pg_catalog.btrim(cashier.local_user_id) <> ''
  ORDER BY staff.display_name, cashier.local_user_id;
END;
$function$;

REVOKE ALL ON FUNCTION public.list_admin_cashiers_with_permissions() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.list_admin_cashiers_with_permissions() TO authenticated, service_role;

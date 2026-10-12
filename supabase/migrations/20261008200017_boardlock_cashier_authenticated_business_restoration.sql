CREATE OR REPLACE FUNCTION public.cashier_restoration_payload(p_membership_id text)
RETURNS TABLE(payload jsonb)
LANGUAGE sql SECURITY DEFINER SET search_path = ''
AS $function$
 SELECT jsonb_build_object(
  'membership_id', m.id,
  'local_user_id', a.local_user_id,
  'business_id', b.id, 'business_name', b.name,
  'branch_id', br.id, 'branch_name', br.name, 'branch_code', br.code,
  'country_code', br.country_code, 'currency_code', br.currency_code,
  'currency_decimal_places', br.currency_decimal_places,
  'currency_symbol', br.currency_symbol, 'timezone_id', br.timezone_id,
  'tax_rounding_mode', br.tax_rounding_mode,
  'default_tax_rate_bps', br.default_tax_rate_bps,
  'branch_address', br.address, 'branch_phone', br.phone,
  'tax_registration_number', br.tax_registration_number,
  'display_name', s.display_name, 'canonical_phone', s.canonical_phone,
  'permissions', jsonb_build_object(
   'CREATE_SALE',m.can_create_sale,'ACCEPT_CASH',m.can_accept_cash,
   'ACCEPT_ONLINE',m.can_accept_online,'CREATE_CREDIT_SALE',m.can_create_credit_sale,
   'USE_COUPON',m.can_use_coupon,'ISSUE_INVOICE',m.can_issue_invoice,
   'ISSUE_RECEIPT',m.can_issue_receipt,'REFUND',m.can_refund,
   'VOID',m.can_void,'CORRECT_AMOUNT',m.can_correct_amount,
   'CHANGE_PAYMENT_METHOD',m.can_change_payment_method,
   'VIEW_BROAD_HISTORY',m.can_view_broad_history,
   'MANAGE_PRODUCTS',m.can_manage_products,'VIEW_REPORTS',m.can_view_reports
  )
 )
 FROM public.business_memberships m
 JOIN private.staff_identities s ON s.id=m.staff_identity_id
 JOIN private.staff_invite_activations a ON a.membership_id=m.id
 JOIN public.businesses b ON b.id=m.business_id
 JOIN public.branches br ON br.id=m.branch_id AND br.business_id=b.id
 WHERE m.id=p_membership_id AND m.role='CASHIER' AND m.is_active
   AND m.auth_user_id IS NULL AND s.is_active AND b.is_active AND br.is_active
 LIMIT 1;
$function$;
REVOKE ALL ON FUNCTION public.cashier_restoration_payload(text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.cashier_restoration_payload(text) TO service_role;
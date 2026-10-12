-- Expose only the authenticated bootstrap wrapper. Its implementation still
-- validates auth.uid() and all tenant inputs, but authenticated callers cannot
-- invoke the tenant-creation implementation directly through private schema.
CREATE OR REPLACE FUNCTION public.bootstrap_admin_tenant(
  p_business_id text, p_business_name text, p_branch_id text, p_branch_name text,
  p_branch_code text, p_country_code text, p_currency_code text,
  p_currency_decimal_places integer, p_currency_symbol text, p_timezone_id text,
  p_tax_rounding_mode text, p_default_tax_rate_bps integer,
  p_branch_address text DEFAULT NULL, p_branch_phone text DEFAULT NULL,
  p_tax_registration_number text DEFAULT NULL, p_local_admin_user_id text DEFAULT NULL,
  p_admin_display_name text DEFAULT NULL, p_admin_username text DEFAULT NULL,
  p_admin_canonical_phone text DEFAULT NULL, p_language_code text DEFAULT 'en',
  p_membership_id text DEFAULT NULL
) RETURNS TABLE(
  business_id text, branch_id text, local_admin_user_id text,
  membership_id text, auth_user_id uuid
)
LANGUAGE sql SECURITY DEFINER SET search_path TO ''
AS $function$
  SELECT * FROM private.bootstrap_admin_tenant_impl(
    p_business_id, p_business_name, p_branch_id, p_branch_name, p_branch_code,
    p_country_code, p_currency_code, p_currency_decimal_places, p_currency_symbol,
    p_timezone_id, p_tax_rounding_mode, p_default_tax_rate_bps,
    p_branch_address, p_branch_phone, p_tax_registration_number,
    p_local_admin_user_id, p_admin_display_name, p_admin_username,
    p_admin_canonical_phone, p_language_code, p_membership_id
  );
$function$;

REVOKE EXECUTE ON FUNCTION private.bootstrap_admin_tenant_impl(
  text,text,text,text,text,text,text,integer,text,text,text,integer,
  text,text,text,text,text,text,text,text,text
) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION private.bootstrap_admin_tenant_impl(
  text,text,text,text,text,text,text,integer,text,text,text,integer,
  text,text,text,text,text,text,text,text,text
) FROM anon;
REVOKE EXECUTE ON FUNCTION private.bootstrap_admin_tenant_impl(
  text,text,text,text,text,text,text,integer,text,text,text,integer,
  text,text,text,text,text,text,text,text,text
) FROM authenticated;

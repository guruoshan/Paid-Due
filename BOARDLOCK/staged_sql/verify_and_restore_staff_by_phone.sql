-- BOARDLOCK: fail-closed retirement of an UNSAFE staged verification function.
-- This does not create an alternative cashier login mechanism.
-- Previous implementation accepted a formatted PIN without PBKDF2 comparison.
-- Do NOT deploy this alongside old insecure RPC authorization.
create or replace function public.verify_and_restore_staff_by_phone(
    p_canonical_phone text, p_pin text
)
returns table (
    membership_id text, local_user_id text, staff_identity_id uuid,
    business_id text, branch_id text, business_name text, branch_name text,
    branch_code text, country_code text, currency_code text,
    currency_decimal_places int, currency_symbol text, timezone_id text,
    tax_rounding_mode text, default_tax_rate_bps int, branch_address text,
    branch_phone text, tax_registration_number text, display_name text,
    canonical_phone text, credential_hash text, credential_salt text,
    permissions jsonb
)
language plpgsql security definer set search_path = '' as $$
begin
    raise exception 'Cashier remote login unavailable pending secure verification'
        using errcode = '42501';
end;
$$;
revoke all on function public.verify_and_restore_staff_by_phone(text,text)
    from public, anon, authenticated, service_role;

-- BOARDLOCK STAGED SQL — FETCH CASHIER METADATA RPC. DO NOT APPLY OR AUTO-DEPLOY.
create or replace function public.fetch_cashier_metadata(
    p_business_id text,
    p_branch_id text,
    p_membership_id text
)
returns table (
    membership_id text,
    business_id text,
    business_name text,
    branch_id text,
    branch_name text,
    branch_code text,
    branch_address text,
    branch_phone text,
    tax_registration_number text,
    country_code text,
    currency_code text,
    currency_decimal_places integer,
    currency_symbol text,
    timezone_id text,
    tax_rounding_mode text,
    default_tax_rate_bps integer,
    role text,
    permissions jsonb,
    display_name text,
    canonical_phone text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_is_active boolean := false;
begin
    -- BOARDLOCK: Verify active staff membership and active branch/business status
    select true into v_is_active
    from public.business_memberships m
    join public.businesses b on b.id = m.business_id and b.is_active = true
    join public.branches br on br.id = m.branch_id and br.business_id = m.business_id and br.is_active = true
    join private.staff_identities si on si.id = m.staff_identity_id and si.is_active = true
    where m.id = p_membership_id
      and m.business_id = p_business_id
      and m.branch_id = p_branch_id
      and m.is_active = true;

    if not coalesce(v_is_active, false) then
        raise exception 'Unauthorized staff metadata access' using errcode = '42501';
    end if;

    return query
    select
        m.id as membership_id,
        b.id as business_id,
        b.name as business_name,
        br.id as branch_id,
        br.name as branch_name,
        br.code as branch_code,
        br.address as branch_address,
        br.phone as branch_phone,
        br.tax_registration_number as tax_registration_number,
        br.country_code as country_code,
        br.currency_code as currency_code,
        br.currency_decimal_places as currency_decimal_places,
        br.currency_symbol as currency_symbol,
        br.timezone_id as timezone_id,
        br.tax_rounding_mode as tax_rounding_mode,
        br.default_tax_rate_bps as default_tax_rate_bps,
        m.role as role,
        jsonb_build_object(
            'can_create_sale', m.can_create_sale,
            'can_accept_cash', m.can_accept_cash,
            'can_accept_online', m.can_accept_online,
            'can_create_credit_sale', m.can_create_credit_sale,
            'can_use_coupon', m.can_use_coupon,
            'can_issue_invoice', m.can_issue_invoice,
            'can_issue_receipt', m.can_issue_receipt,
            'can_refund', m.can_refund,
            'can_void', m.can_void,
            'can_correct_amount', m.can_correct_amount,
            'can_change_payment_method', m.can_change_payment_method,
            'can_view_broad_history', m.can_view_broad_history,
            'can_manage_products', m.can_manage_products,
            'can_view_reports', m.can_view_reports
        ) as permissions,
        si.display_name as display_name,
        si.canonical_phone as canonical_phone
    from public.business_memberships m
    join public.businesses b on b.id = m.business_id
    join public.branches br on br.id = m.branch_id
    join private.staff_identities si on si.id = m.staff_identity_id
    where m.id = p_membership_id
      and m.business_id = p_business_id
      and m.branch_id = p_branch_id;
end;
$$;

revoke all on function public.fetch_cashier_metadata(text, text, text)
    from public, anon, authenticated, service_role;
grant execute on function public.fetch_cashier_metadata(text, text, text)
    to service_role;

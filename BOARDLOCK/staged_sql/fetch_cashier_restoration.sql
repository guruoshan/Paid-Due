-- Paid & Due: return the authenticated cashier's complete restoration payload.
-- Called only by the API after the cashier's PIN has been verified.
-- The caller uses the service-role key; the RPC is not exposed to app roles.
create or replace function public.fetch_cashier_restoration(
    p_membership_id text
)
returns table (
    membership_id text,
    local_user_id text,
    staff_identity_id uuid,
    business_id text,
    branch_id text,
    business_name text,
    branch_name text,
    branch_code text,
    country_code text,
    currency_code text,
    currency_decimal_places integer,
    currency_symbol text,
    timezone_id text,
    tax_rounding_mode text,
    default_tax_rate_bps integer,
    branch_address text,
    branch_phone text,
    tax_registration_number text,
    display_name text,
    canonical_phone text,
    permissions jsonb
)
language sql
security definer
set search_path = ''
stable
as $$
    select
        m.id,
        coalesce(m.local_user_id, activation.local_user_id),
        si.id,
        b.id,
        br.id,
        b.name,
        br.name,
        br.code,
        br.country_code,
        br.currency_code,
        br.currency_decimal_places,
        br.currency_symbol,
        br.timezone_id,
        br.tax_rounding_mode,
        br.default_tax_rate_bps,
        br.address,
        br.phone,
        br.tax_registration_number,
        si.display_name,
        si.canonical_phone,
        jsonb_build_object(
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
    from public.business_memberships m
    join public.businesses b
      on b.id = m.business_id and b.is_active = true
    join public.branches br
      on br.id = m.branch_id
     and br.business_id = m.business_id
     and br.is_active = true
    join private.staff_identities si
      on si.id = m.staff_identity_id and si.is_active = true
    left join private.staff_invite_activations activation
      on activation.membership_id = m.id
     and activation.staff_identity_id = si.id
    where m.id = p_membership_id
      and m.role = 'CASHIER'
      and m.is_active = true
      and coalesce(m.local_user_id, activation.local_user_id) is not null
      and si.credential_hash is not null
      and si.credential_salt is not null
    limit 1;
$$;

revoke all on function public.fetch_cashier_restoration(text)
    from public, anon, authenticated;
grant execute on function public.fetch_cashier_restoration(text)
    to service_role;

comment on function public.fetch_cashier_restoration(text) is
'Returns restoration fields and permissions for a verified cashier membership. Service-role only; API must verify PIN before returning the payload.';

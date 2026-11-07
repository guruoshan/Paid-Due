-- SOURCE RECONCILIATION ONLY. NOT A MIGRATION. DO NOT APPLY.
-- Recorded from production catalog on 2026-11-07, normalised body SHA-256 bbdad76fb26c6705a9f01e1af617fe0dc0317c18bbd45e3635b8c36fc1ab536c.
-- Applied out of band before this repository tracked it. Migration ledger status: see reconciliation doc.

CREATE OR REPLACE FUNCTION public.restore_admin_by_auth_id()
 RETURNS TABLE(membership_id text, local_user_id text, staff_identity_id uuid, business_id text, branch_id text, business_name text, branch_name text, branch_code text, country_code text, currency_code text, currency_decimal_places integer, currency_symbol text, timezone_id text, tax_rounding_mode text, default_tax_rate_bps integer, branch_address text, branch_phone text, tax_registration_number text, display_name text, canonical_phone text, credential_hash text, credential_salt text, permissions jsonb)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    v_user_id uuid;
    v_membership_count bigint;
begin
    v_user_id := (select auth.uid());

    if v_user_id is null then
        raise exception 'Authentication required'
            using errcode = '28000';
    end if;

    -- Require exactly one eligible ADMIN membership.
    -- Never choose an arbitrary tenant.

    select count(*)
    into v_membership_count
    from public.business_memberships bm
    join public.businesses b
      on b.id = bm.business_id
    join public.branches br
      on br.id = bm.branch_id
     and br.business_id = bm.business_id
    join public.profiles p
      on p.auth_user_id = bm.auth_user_id
    where bm.auth_user_id = v_user_id
      and bm.role = 'ADMIN'
      and bm.is_active = true
      and b.is_active = true
      and br.is_active = true
      and p.is_active = true
      and bm.local_user_id is not null
      and btrim(bm.local_user_id) <> '';

    if v_membership_count <> 1 then
        -- No data returned for missing or ambiguous authority.
        return;
    end if;

    return query
    select
        bm.id,
        bm.local_user_id,
        bm.auth_user_id,
        bm.business_id,
        bm.branch_id,
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
        p.display_name,
        p.canonical_phone,

        -- No authentication secrets leave the server.
        null::text,
        null::text,

        jsonb_build_object(
            'CREATE_SALE', bm.can_create_sale,
            'ACCEPT_CASH', bm.can_accept_cash,
            'ACCEPT_ONLINE', bm.can_accept_online,
            'CREATE_CREDIT_SALE', bm.can_create_credit_sale,
            'USE_COUPON', bm.can_use_coupon,
            'ISSUE_INVOICE', bm.can_issue_invoice,
            'ISSUE_RECEIPT', bm.can_issue_receipt,
            'REFUND', bm.can_refund,
            'VOID', bm.can_void,
            'CORRECT_AMOUNT', bm.can_correct_amount,
            'CHANGE_PAYMENT_METHOD',
                bm.can_change_payment_method,
            'VIEW_BROAD_HISTORY',
                bm.can_view_broad_history,
            'MANAGE_PRODUCTS', bm.can_manage_products,
            'VIEW_REPORTS', bm.can_view_reports
        )
    from public.business_memberships bm
    join public.businesses b
      on b.id = bm.business_id
    join public.branches br
      on br.id = bm.branch_id
     and br.business_id = bm.business_id
    join public.profiles p
      on p.auth_user_id = bm.auth_user_id
    where bm.auth_user_id = v_user_id
      and bm.role = 'ADMIN'
      and bm.is_active = true
      and b.is_active = true
      and br.is_active = true
      and p.is_active = true
      and bm.local_user_id is not null
      and btrim(bm.local_user_id) <> ''
      and bm.can_create_sale is not null
      and bm.can_accept_cash is not null
      and bm.can_accept_online is not null
      and bm.can_create_credit_sale is not null
      and bm.can_use_coupon is not null
      and bm.can_issue_invoice is not null
      and bm.can_issue_receipt is not null
      and bm.can_refund is not null
      and bm.can_void is not null
      and bm.can_correct_amount is not null
      and bm.can_change_payment_method is not null
      and bm.can_view_broad_history is not null
      and bm.can_manage_products is not null
      and bm.can_view_reports is not null;
end;
$function$

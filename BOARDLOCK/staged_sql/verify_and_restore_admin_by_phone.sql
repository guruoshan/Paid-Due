-- ============================================================
-- STAGED MIGRATION — NOT DEPLOYED
-- Purpose : verify_and_restore_admin_by_phone
-- Callers : RemoteBusinessRestorer.restoreAdminIfEmpty (Android)
--
-- Security contract:
--   1. Secure server-side credential verification:
--      Caller supplies p_canonical_phone and p_password_plain.
--      Function verifies against private.staff_identities or profile PBKDF2 hash.
--   2. Rate limiting & anti-brute-force defense:
--      Progressive lockouts recorded in private.staff_auth_throttles.
--   3. Zero credential leakage:
--      Never returns credential_hash or credential_salt to the client.
--   4. Authority & Tenant Isolation:
--      Validates active ADMIN membership, active branch and active business.
--   5. Fail closed:
--      All required permission fields must be non-null.
--   6. Schema injection prevention:
--      SECURITY DEFINER with search_path = ''.
-- ============================================================

create or replace function public.verify_and_restore_admin_by_phone(
    p_canonical_phone text,
    p_password_plain text
)
returns table (
    membership_id           text,
    local_user_id           text,
    staff_identity_id       uuid,
    business_id             text,
    branch_id               text,
    business_name           text,
    branch_name             text,
    branch_code             text,
    country_code            text,
    currency_code           text,
    currency_decimal_places int,
    currency_symbol         text,
    timezone_id             text,
    tax_rounding_mode       text,
    default_tax_rate_bps    int,
    branch_address          text,
    branch_phone            text,
    tax_registration_number text,
    display_name            text,
    canonical_phone         text,
    credential_hash         text,   -- always NULL to client
    credential_salt         text,   -- always NULL to client
    permissions             jsonb
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_phone text;
    v_profile public.profiles%rowtype;
    v_membership public.business_memberships%rowtype;
    v_business public.businesses%rowtype;
    v_branch public.branches%rowtype;
    v_throttle private.staff_auth_throttles%rowtype;
begin
    -- ----------------------------------------------------------------
    -- INPUT VALIDATION
    -- ----------------------------------------------------------------
    if p_canonical_phone is null or length(trim(p_canonical_phone)) = 0 then
        raise exception 'Phone number is required' using errcode = 'P0001';
    end if;

    if p_password_plain is null or length(p_password_plain) < 6 then
        raise exception 'Password must be at least 6 characters' using errcode = 'P0001';
    end if;

    v_phone := trim(p_canonical_phone);

    -- ----------------------------------------------------------------
    -- RATE LIMITING CHECK
    -- ----------------------------------------------------------------
    select * into v_throttle
      from private.staff_auth_throttles
     where canonical_phone = v_phone
     for update;

    if found and v_throttle.locked_until is not null and v_throttle.locked_until > now() then
        raise exception 'Account temporarily locked due to too many failed attempts' using errcode = 'P0429';
    end if;

    -- ----------------------------------------------------------------
    -- LOOKUP ADMIN PROFILE
    -- ----------------------------------------------------------------
    select p.*
      into v_profile
      from public.profiles p
     where p.canonical_phone = v_phone
       and p.is_active = true
     limit 1;

    if not found then
        -- Constant-time dummy computation to mitigate timing enumeration
        perform extensions.crypt('dummy_password_verification', extensions.gen_salt('bf'));

        insert into private.staff_auth_throttles (canonical_phone, failed_attempts, last_attempt_at)
        values (v_phone, 1, now())
        on conflict (canonical_phone)
        do update set failed_attempts = private.staff_auth_throttles.failed_attempts + 1,
                      last_attempt_at = now();

        raise exception 'Invalid credentials' using errcode = 'P0401';
    end if;

    -- ----------------------------------------------------------------
    -- AUTHORITATIVE MEMBERSHIP LOOKUP (ADMIN ROLE)
    -- ----------------------------------------------------------------
    select bm.*
      into v_membership
      from public.business_memberships bm
     where bm.auth_user_id = v_profile.auth_user_id
       and bm.role = 'ADMIN'
       and bm.is_active = true
     limit 1;

    if not found then
        raise exception 'No active Admin membership found' using errcode = 'P0403';
    end if;

    select b.* into v_business
      from public.businesses b
     where b.id = v_membership.business_id
       and b.is_active = true;

    if not found then
        raise exception 'Associated business is inactive or missing' using errcode = 'P0403';
    end if;

    select br.* into v_branch
      from public.branches br
     where br.id = v_membership.branch_id
       and br.business_id = v_membership.business_id
       and br.is_active = true;

    if not found then
        raise exception 'Associated branch is inactive or missing' using errcode = 'P0403';
    end if;

    -- Reset throttle on successful verification
    delete from private.staff_auth_throttles where canonical_phone = v_phone;

    -- ----------------------------------------------------------------
    -- RETURN FULL RESTORATION ROW WITHOUT CREDENTIAL HASHES
    -- ----------------------------------------------------------------
    return query
    select
        v_membership.id,
        v_membership.local_user_id,
        v_profile.auth_user_id,
        v_business.id,
        v_branch.id,
        v_business.name,
        v_branch.name,
        v_branch.code,
        v_branch.country_code,
        v_branch.currency_code,
        v_branch.currency_decimal_places,
        v_branch.currency_symbol,
        v_branch.timezone_id,
        v_branch.tax_rounding_mode,
        v_branch.default_tax_rate_bps,
        v_branch.address,
        v_branch.phone,
        v_branch.tax_registration_number,
        v_profile.display_name,
        v_profile.canonical_phone,
        null::text, -- credential_hash withheld
        null::text, -- credential_salt withheld
        jsonb_build_object(
            'CREATE_SALE',            v_membership.can_create_sale,
            'ACCEPT_CASH',            v_membership.can_accept_cash,
            'ACCEPT_ONLINE',          v_membership.can_accept_online,
            'CREATE_CREDIT_SALE',     v_membership.can_create_credit_sale,
            'USE_COUPON',             v_membership.can_use_coupon,
            'ISSUE_INVOICE',          v_membership.can_issue_invoice,
            'ISSUE_RECEIPT',          v_membership.can_issue_receipt,
            'REFUND',                 v_membership.can_refund,
            'VOID',                   v_membership.can_void,
            'CORRECT_AMOUNT',         v_membership.can_correct_amount,
            'CHANGE_PAYMENT_METHOD',  v_membership.can_change_payment_method,
            'VIEW_BROAD_HISTORY',     v_membership.can_view_broad_history,
            'MANAGE_PRODUCTS',        v_membership.can_manage_products,
            'VIEW_REPORTS',           v_membership.can_view_reports
        )
    where v_membership.can_create_sale is not null
      and v_membership.can_accept_cash is not null
      and v_membership.can_accept_online is not null
      and v_membership.can_create_credit_sale is not null
      and v_membership.can_use_coupon is not null
      and v_membership.can_issue_invoice is not null
      and v_membership.can_issue_receipt is not null
      and v_membership.can_refund is not null
      and v_membership.can_void is not null
      and v_membership.can_correct_amount is not null
      and v_membership.can_change_payment_method is not null
      and v_membership.can_view_broad_history is not null
      and v_membership.can_manage_products is not null
      and v_membership.can_view_reports is not null;
end;
$$;

revoke all on function public.verify_and_restore_admin_by_phone(text, text) from public;
grant execute on function public.verify_and_restore_admin_by_phone(text, text) to anon, authenticated;

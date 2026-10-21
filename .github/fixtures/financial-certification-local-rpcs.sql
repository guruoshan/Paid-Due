-- LOCAL CERTIFICATION FIXTURE ONLY. Applied only after `supabase start` in the disposable
-- GitHub Actions local stack. This is deliberately not a production migration: the matching
-- backend RPC remains staged for separate backend review. Authenticated role/tenant checks and
-- digest verification below are retained exactly; this fixture does not bypass authorization.
create or replace function public.fetch_financial_envelopes_v3(
    p_business_id text,
    p_after_received_at timestamptz default null,
    p_after_operation_id text default null,
    p_limit integer default 100
)
returns table (
    operation_id text,
    business_id text,
    branch_id text,
    operation_type text,
    occurred_at timestamptz,
    received_at timestamptz,
    canonical_text text,
    canonical_payload jsonb,
    canonical_sha256 text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if p_limit is null or p_limit < 1 or p_limit > 100 then
        raise exception 'Invalid page limit' using errcode = '22023';
    end if;
    if (p_after_received_at is null) <> (p_after_operation_id is null) then
        raise exception 'Invalid cursor' using errcode = '22023';
    end if;
    if not exists (
        select 1
        from public.business_memberships m
        join public.businesses b on b.id = m.business_id and b.is_active
        join public.branches br on br.id = m.branch_id
            and br.business_id = m.business_id and br.is_active
        join public.profiles p on p.auth_user_id = m.auth_user_id and p.is_active
        where m.auth_user_id = v_uid
          and m.role = 'ADMIN'
          and m.is_active
          and m.staff_identity_id is null
          and m.business_id = p_business_id
    ) then
        raise exception 'Admin authorization required' using errcode = '42501';
    end if;

    return query
    select e.operation_id, e.business_id, e.branch_id, e.operation_type,
           e.occurred_at, e.received_at, e.canonical_text,
           e.canonical_payload, e.canonical_sha256
    from private.financial_operation_envelopes e
    where e.business_id = p_business_id
      and (
          p_after_received_at is null
          or (e.received_at, e.operation_id) > (p_after_received_at, p_after_operation_id)
      )
      and exists (
          select 1 from public.business_memberships m
          where m.auth_user_id = v_uid
            and m.business_id = e.business_id
            and m.branch_id = e.branch_id
            and m.role = 'ADMIN'
            and m.is_active
            and m.staff_identity_id is null
      )
    order by e.received_at, e.operation_id
    limit p_limit;
end;
$$;

revoke all on function public.fetch_financial_envelopes_v3(text,timestamptz,text,integer)
    from public, anon, authenticated, service_role;
grant execute on function public.fetch_financial_envelopes_v3(text,timestamptz,text,integer)
    to authenticated;


-- Same local-only rule applies to the app's real Admin login scope-verification RPC.
create or replace function public.restore_admin_by_auth_id()
returns table (
    -- IDs
    membership_id           text,
    local_user_id           text,
    staff_identity_id       uuid,       -- auth_user_id exposed as identity ref
    business_id             text,
    branch_id               text,
    -- Business/branch display
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
    -- Profile
    display_name            text,
    canonical_phone         text,
    -- Credentials intentionally omitted — no hashes returned
    credential_hash         text,   -- always NULL; field kept for Android DTO compat
    credential_salt         text,   -- always NULL
    -- Permissions (ADMIN always gets all permissions)
    permissions             jsonb
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_auth_user_id uuid;
begin
    -- ----------------------------------------------------------------
    -- STEP 1: Require an authenticated session
    -- ----------------------------------------------------------------
    v_auth_user_id := (select auth.uid());

    if v_auth_user_id is null then
        raise exception 'Authentication required'
            using errcode = 'P0401';
    end if;

    -- ----------------------------------------------------------------
    -- STEP 2: Return the active ADMIN membership for this caller only.
    --         The WHERE clause on auth_user_id = v_auth_user_id and
    --         role = 'ADMIN' enforces tenant isolation and role check.
    -- ----------------------------------------------------------------
    return query
    select
        bm.id                           as membership_id,
        bm.local_user_id                as local_user_id,
        bm.auth_user_id                 as staff_identity_id,
        bm.business_id                  as business_id,
        bm.branch_id                    as branch_id,

        b.name                          as business_name,
        br.name                         as branch_name,
        br.code                         as branch_code,
        br.country_code                 as country_code,
        br.currency_code                as currency_code,
        br.currency_decimal_places      as currency_decimal_places,
        br.currency_symbol              as currency_symbol,
        br.timezone_id                  as timezone_id,
        br.tax_rounding_mode            as tax_rounding_mode,
        br.default_tax_rate_bps         as default_tax_rate_bps,
        br.address                      as branch_address,
        br.phone                        as branch_phone,
        br.tax_registration_number      as tax_registration_number,

        p.display_name                  as display_name,
        p.canonical_phone               as canonical_phone,

        null::text                      as credential_hash,   -- intentionally withheld
        null::text                      as credential_salt,   -- intentionally withheld

        jsonb_build_object(
            'CREATE_SALE',            bm.can_create_sale,
            'ACCEPT_CASH',            bm.can_accept_cash,
            'ACCEPT_ONLINE',          bm.can_accept_online,
            'CREATE_CREDIT_SALE',     bm.can_create_credit_sale,
            'USE_COUPON',             bm.can_use_coupon,
            'ISSUE_INVOICE',          bm.can_issue_invoice,
            'ISSUE_RECEIPT',          bm.can_issue_receipt,
            'REFUND',                 bm.can_refund,
            'VOID',                   bm.can_void,
            'CORRECT_AMOUNT',         bm.can_correct_amount,
            'CHANGE_PAYMENT_METHOD',  bm.can_change_payment_method,
            'VIEW_BROAD_HISTORY',     bm.can_view_broad_history,
            'MANAGE_PRODUCTS',        bm.can_manage_products,
            'VIEW_REPORTS',           bm.can_view_reports
        )                               as permissions

    from   public.business_memberships bm
    join   public.businesses            b   on b.id  = bm.business_id
    join   public.branches              br  on br.id = bm.branch_id
                                          and br.business_id = bm.business_id
    join   public.profiles              p   on p.auth_user_id = bm.auth_user_id

    where  bm.auth_user_id = v_auth_user_id    -- tenant isolation via auth session
      and  bm.role         = 'ADMIN'            -- role gate — server-enforced
      and  bm.is_active    = true               -- only active memberships
      and  b.is_active     = true
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
$$;

-- Grant execute to the authenticated role only (no anon access)
revoke all on function public.restore_admin_by_auth_id() from public, anon;
grant  execute on function public.restore_admin_by_auth_id() to authenticated;

comment on function public.restore_admin_by_auth_id() is
'Returns restoration data for the signed-in admin''s own tenant. '
'Requires a valid Supabase session. Returns no credential hashes.';

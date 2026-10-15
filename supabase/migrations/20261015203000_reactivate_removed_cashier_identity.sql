-- Reuse an existing cashier phone identity only when all its cloud memberships
-- are removed/inactive. Preserve prior memberships and financial/audit history;
-- issue a new membership with this invite's permissions and replace the PIN verifier.
CREATE OR REPLACE FUNCTION public.activate_staff_invite(p_token_hash text, p_credential_hash text, p_credential_salt text)
 RETURNS TABLE(outcome text, staff_identity_id uuid, membership_id text, local_user_id text, business_id text, business_name text, branch_id text, branch_name text, branch_code text, country_code text, currency_code text, currency_decimal_places integer, currency_symbol text, timezone_id text, tax_rounding_mode text, default_tax_rate_bps integer, branch_address text, branch_phone text, tax_registration_number text, display_name text, canonical_phone text, role text, permissions jsonb, activated_at timestamp with time zone)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    v_invite private.staff_invites%rowtype;
    v_activation private.staff_invite_activations%rowtype;
    v_existing_staff private.staff_identities%rowtype;

    v_business public.businesses%rowtype;
    v_branch public.branches%rowtype;

    v_staff_identity_id uuid;
    v_membership_id text;
    v_local_user_id text;
    v_activated_at timestamptz;
begin

    -- --------------------------------------------------------------
    -- INPUT VALIDATION
    -- --------------------------------------------------------------

    if p_token_hash is null
       or p_token_hash !~ '^[0-9a-f]{64}$' then

        return query
        select
            'INVALID_INVITE'::text,
            null::uuid,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::integer,
            null::text,
            null::text,
            null::text,
            null::integer,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::jsonb,
            null::timestamptz;

        return;
    end if;


    /*
     * Require the current Paid&Due PIN-hash format.
     *
     * This prevents a caller from deliberately supplying a weak
     * credential verifier.
     */
    if p_credential_hash is null
       or p_credential_hash
            !~ '^pbkdf2-sha256:(100000|600000):[0-9a-f]{64}$' then

        raise exception 'Invalid credential verifier'
            using errcode = 'P0001';
    end if;


    if p_credential_salt is null
       or p_credential_salt !~ '^[0-9a-f]{32}$' then

        raise exception 'Invalid credential salt'
            using errcode = 'P0001';
    end if;


    -- --------------------------------------------------------------
    -- LOCK AUTHORITATIVE INVITE
    --
    -- FOR UPDATE serializes competing activation attempts.
    -- --------------------------------------------------------------

    select i.*
      into v_invite
      from private.staff_invites i
     where i.token_hash = lower(p_token_hash)
     for update;


    if not found then

        return query
        select
            'INVALID_INVITE'::text,
            null::uuid,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::integer,
            null::text,
            null::text,
            null::text,
            null::integer,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::jsonb,
            null::timestamptz;

        return;
    end if;


    -- --------------------------------------------------------------
    -- EXACT RETRY / RECOVERY
    --
    -- If this exact invitation already committed successfully,
    -- return its authoritative result only when the persisted
    -- verifier matches this activation attempt.
    --
    -- This supports crash/network recovery without creating
    -- another Cashier or another membership.
    -- --------------------------------------------------------------

    if v_invite.used_at is not null then

        select a.*
          into v_activation
          from private.staff_invite_activations a
         where a.invite_id = v_invite.id;

        if not found then
            return query
            select
                'INVALID_INVITE'::text,
                null::uuid,
                null::text,
                null::text,
                null::text,
                null::text,
                null::text,
                null::text,
                null::text,
                null::text,
                null::text,
                null::integer,
                null::text,
                null::text,
                null::text,
                null::integer,
                null::text,
                null::text,
                null::text,
                null::text,
                null::text,
                null::text,
                null::jsonb,
                null::timestamptz;

            return;
        end if;


        select s.*
          into v_existing_staff
          from private.staff_identities s
         where s.id = v_activation.staff_identity_id;


        if not found
           or v_existing_staff.credential_hash
                is distinct from p_credential_hash
           or v_existing_staff.credential_salt
                is distinct from p_credential_salt then

            return query
            select
                'INVALID_INVITE'::text,
                null::uuid,
                null::text,
                null::text,
                null::text,
                null::text,
                null::text,
                null::text,
                null::text,
                null::text,
                null::text,
                null::integer,
                null::text,
                null::text,
                null::text,
                null::integer,
                null::text,
                null::text,
                null::text,
                null::text,
                null::text,
                null::text,
                null::jsonb,
                null::timestamptz;

            return;
        end if;


        select b.*
          into v_business
          from public.businesses b
         where b.id = v_invite.business_id;


        select br.*
          into v_branch
          from public.branches br
         where br.id = v_invite.branch_id
           and br.business_id = v_invite.business_id;


        return query
        select
            'ALREADY_ACTIVATED'::text,

            v_activation.staff_identity_id,
            v_activation.membership_id,
            v_activation.local_user_id,

            v_invite.business_id,
            v_business.name,

            v_invite.branch_id,
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

            v_invite.display_name,
            v_invite.canonical_phone,
            'CASHIER'::text,
            v_invite.permissions,

            v_activation.activated_at;

        return;
    end if;


    -- --------------------------------------------------------------
    -- INVITE MUST STILL BE USABLE
    -- --------------------------------------------------------------

    if v_invite.revoked_at is not null
       or v_invite.expires_at <= now()
       or v_invite.role <> 'CASHIER' then

        return query
        select
            'INVALID_INVITE'::text,
            null::uuid,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::integer,
            null::text,
            null::text,
            null::text,
            null::integer,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::text,
            null::jsonb,
            null::timestamptz;

        return;
    end if;


    -- --------------------------------------------------------------
    -- BUSINESS + BRANCH MUST STILL BE ACTIVE AND CONSISTENT
    -- --------------------------------------------------------------

    select b.*
      into v_business
      from public.businesses b
     where b.id = v_invite.business_id
       and b.is_active = true;

    if not found then
        raise exception 'Inactive or missing business'
            using errcode = 'P0001';
    end if;


    select br.*
      into v_branch
      from public.branches br
     where br.id = v_invite.branch_id
       and br.business_id = v_invite.business_id
       and br.is_active = true;

    if not found then
        raise exception 'Inactive or missing branch'
            using errcode = 'P0001';
    end if;


    -- --------------------------------------------------------------
    -- DO NOT SILENTLY REUSE / RESET AN EXISTING STAFF CREDENTIAL
    --
    -- Multi-business Cashier identity can be designed separately.
    -- For this activation phase we fail closed rather than changing
    -- somebody else's existing PIN.
    -- --------------------------------------------------------------

    select s.*
      into v_existing_staff
      from private.staff_identities s
     where s.canonical_phone = v_invite.canonical_phone
     for update;


    if found then
      if exists (
        select 1 from public.business_memberships bm
        where bm.staff_identity_id = v_existing_staff.id
          and bm.is_active = true and bm.removed_at is null
      ) then
        return query
        select
            'PHONE_ALREADY_REGISTERED'::text,
            null::uuid,
            null::text,
            null::text,

            v_invite.business_id,
            v_business.name,

            v_invite.branch_id,
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

            v_invite.display_name,
            v_invite.canonical_phone,
            'CASHIER'::text,
            v_invite.permissions,

            null::timestamptz;

        return;

      end if;
      select bm.local_user_id into v_local_user_id
      from public.business_memberships bm
      where bm.staff_identity_id = v_existing_staff.id
        and bm.local_user_id is not null
      order by bm.updated_at desc nulls last, bm.created_at desc
      limit 1;
      v_staff_identity_id := v_existing_staff.id;
      v_membership_id := gen_random_uuid()::text;
      v_local_user_id := coalesce(v_local_user_id, gen_random_uuid()::text);
      v_activated_at := now();
    end if;

    -- --------------------------------------------------------------
    -- AUTHORITATIVE IDS
    --
    -- Generated server-side. Caller cannot choose them.
    -- --------------------------------------------------------------

    IF v_staff_identity_id IS NULL THEN
      v_staff_identity_id := gen_random_uuid();
      v_membership_id := gen_random_uuid()::text;
      v_local_user_id := gen_random_uuid()::text;
      v_activated_at := now();
    END IF;


    -- --------------------------------------------------------------
    -- STAFF IDENTITY
    -- --------------------------------------------------------------

    if v_existing_staff.id is null then
    insert into private.staff_identities (
        id,
        canonical_phone,
        display_name,
        credential_hash,
        credential_salt,
        is_active,
        created_at,
        updated_at
    )
    values (
        v_staff_identity_id,
        v_invite.canonical_phone,
        trim(v_invite.display_name),
        p_credential_hash,
        p_credential_salt,
        true,
        v_activated_at,
        v_activated_at
    );


    else
      update private.staff_identities
         set canonical_phone = v_invite.canonical_phone,
             display_name = trim(v_invite.display_name),
             credential_hash = p_credential_hash,
             credential_salt = p_credential_salt,
             is_active = true,
             updated_at = v_activated_at
       where id = v_staff_identity_id;
    end if;

    -- --------------------------------------------------------------
    -- CASHIER MEMBERSHIP
    --
    -- Permission values come exclusively from the invitation.
    -- Missing properties use Paid&Due's existing membership defaults.
    -- --------------------------------------------------------------

    insert into public.business_memberships (
        id,
        business_id,
        branch_id,

        auth_user_id,
        staff_identity_id,
        local_user_id,

        role,

        can_create_sale,
        can_accept_cash,
        can_accept_online,
        can_create_credit_sale,
        can_use_coupon,
        can_issue_invoice,
        can_issue_receipt,
        can_refund,
        can_void,
        can_correct_amount,
        can_change_payment_method,
        can_view_broad_history,
        can_manage_products,
        can_view_reports,

        is_active,
        created_at,
        updated_at
    )
    values (
        v_membership_id,
        v_invite.business_id,
        v_invite.branch_id,

        null,
        v_staff_identity_id,
        v_local_user_id,

        'CASHIER',

        coalesce(
            (v_invite.permissions ->> 'CREATE_SALE')::boolean,
            true
        ),

        coalesce(
            (v_invite.permissions ->> 'ACCEPT_CASH')::boolean,
            true
        ),

        coalesce(
            (v_invite.permissions ->> 'ACCEPT_ONLINE')::boolean,
            true
        ),

        coalesce(
            (v_invite.permissions ->> 'CREATE_CREDIT_SALE')::boolean,
            false
        ),

        coalesce(
            (v_invite.permissions ->> 'USE_COUPON')::boolean,
            false
        ),

        coalesce(
            (v_invite.permissions ->> 'ISSUE_INVOICE')::boolean,
            true
        ),

        coalesce(
            (v_invite.permissions ->> 'ISSUE_RECEIPT')::boolean,
            true
        ),

        coalesce(
            (v_invite.permissions ->> 'REFUND')::boolean,
            false
        ),

        coalesce(
            (v_invite.permissions ->> 'VOID')::boolean,
            false
        ),

        coalesce(
            (v_invite.permissions ->> 'CORRECT_AMOUNT')::boolean,
            false
        ),

        coalesce(
            (v_invite.permissions ->> 'CHANGE_PAYMENT_METHOD')::boolean,
            false
        ),

        coalesce(
            (v_invite.permissions ->> 'VIEW_BROAD_HISTORY')::boolean,
            false
        ),

        coalesce(
            (v_invite.permissions ->> 'MANAGE_PRODUCTS')::boolean,
            false
        ),

        coalesce(
            (v_invite.permissions ->> 'VIEW_REPORTS')::boolean,
            false
        ),

        true,
        v_activated_at,
        v_activated_at
    );


    -- --------------------------------------------------------------
    -- DURABLE ACTIVATION EVIDENCE
    -- --------------------------------------------------------------

    insert into private.staff_invite_activations (
        invite_id,
        staff_identity_id,
        membership_id,
        local_user_id,
        activated_at
    )
    values (
        v_invite.id,
        v_staff_identity_id,
        v_membership_id,
        v_local_user_id,
        v_activated_at
    );


    -- --------------------------------------------------------------
    -- CONSUME INVITATION LAST
    --
    -- Still in this same PostgreSQL transaction.
    -- Any later exception rolls the entire activation back.
    -- --------------------------------------------------------------

    update private.staff_invites i
       set used_at = v_activated_at
     where i.id = v_invite.id
       and i.used_at is null
       and i.revoked_at is null;


    if not found then
        raise exception 'Invitation consumption race detected'
            using errcode = 'P0001';
    end if;


    -- --------------------------------------------------------------
    -- AUTHORITATIVE RESULT
    -- --------------------------------------------------------------

    return query
    select
        'ACTIVATED'::text,

        v_staff_identity_id,
        v_membership_id,
        v_local_user_id,

        v_invite.business_id,
        v_business.name,

        v_invite.branch_id,
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

        v_invite.display_name,
        v_invite.canonical_phone,
        'CASHIER'::text,
        v_invite.permissions,

        v_activated_at;
end;
$function$

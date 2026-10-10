-- Paid&Due Supabase Auth-backed staff invitations and activation.
-- Applied to the live project before this source was recorded.
-- All invitations now require an email address; Supabase Auth sends the email.
ALTER TABLE private.staff_invites
  ADD COLUMN IF NOT EXISTS invited_email text;

CREATE INDEX IF NOT EXISTS idx_staff_invites_invited_email
  ON private.staff_invites (lower(invited_email))
  WHERE invited_email IS NOT NULL;

ALTER TABLE private.staff_invites
  ALTER COLUMN invited_email SET NOT NULL;

-- Remove superseded Auth activation overloads before defining the current contract.
DROP FUNCTION IF EXISTS public.activate_staff_invite_for_auth(text,uuid);
DROP FUNCTION IF EXISTS public.activate_staff_invite_for_auth(text,uuid,text,text);

CREATE OR REPLACE FUNCTION public.check_auth_rate_limit(p_scope text, p_bucket_key text)
 RETURNS TABLE(allowed boolean, retry_after_seconds integer)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
    v_limit integer;
    v_window_seconds integer;
    v_attempts integer;
    v_started timestamptz;
    v_now timestamptz := clock_timestamp();
BEGIN
    IF p_scope = 'LOGIN' THEN
        v_limit := 5;
        v_window_seconds := 300;
    ELSIF p_scope = 'ACTIVATE' THEN
        v_limit := 5;
        v_window_seconds := 3600;
    ELSE
        RAISE EXCEPTION 'Invalid scope'
        USING ERRCODE = '22023';
    END IF;

    IF p_bucket_key IS NULL OR
       p_bucket_key !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Invalid bucket key'
        USING ERRCODE = '22023';
    END IF;

    -- NEW: occasionally delete buckets idle for over a day (about 1 call in 50),
    -- so random phone numbers or tokens cannot grow this table forever.
    IF random() < 0.02 THEN
        DELETE FROM private.auth_rate_limits
        WHERE updated_at < v_now - interval '1 day';
    END IF;

    INSERT INTO private.auth_rate_limits AS current_row (
        bucket_key,
        window_started_at,
        attempts,
        updated_at
    )
    VALUES (
        p_bucket_key,
        v_now,
        1,
        v_now
    )
    ON CONFLICT (bucket_key)
    DO UPDATE SET
        attempts = CASE
            WHEN current_row.window_started_at
                 <= v_now - make_interval(secs => v_window_seconds)
            THEN 1
            ELSE LEAST(current_row.attempts + 1, v_limit + 1)
        END,
        window_started_at = CASE
            WHEN current_row.window_started_at
                 <= v_now - make_interval(secs => v_window_seconds)
            THEN v_now
            ELSE current_row.window_started_at
        END,
        updated_at = v_now
    RETURNING
        current_row.attempts,
        current_row.window_started_at
    INTO v_attempts, v_started;

    RETURN QUERY SELECT
        v_attempts <= v_limit,
        CASE
            WHEN v_attempts <= v_limit THEN 0
            ELSE GREATEST(
                1,
                CEIL(
                    EXTRACT(EPOCH FROM (
                        v_started
                        + make_interval(secs => v_window_seconds)
                        - v_now
                    ))
                )::integer
            )
        END;
END;
$function$


CREATE OR REPLACE FUNCTION public.create_staff_invite_for_email(p_token_hash text, p_business_id text, p_branch_id text, p_display_name text, p_canonical_phone text, p_invited_email text, p_permissions jsonb, p_created_by_auth_user_id uuid)
 RETURNS TABLE(invite_id uuid, expires_at timestamp with time zone)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
  v_invite_id uuid;
  v_expires_at timestamptz;
BEGIN
  IF p_created_by_auth_user_id IS NULL THEN
    RAISE EXCEPTION 'Admin identity required' USING ERRCODE = '42501';
  END IF;
  IF p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$' THEN
    RAISE EXCEPTION 'Invalid invitation token hash' USING ERRCODE = '22023';
  END IF;
  IF p_display_name IS NULL OR length(trim(p_display_name)) < 1 OR length(trim(p_display_name)) > 100 THEN
    RAISE EXCEPTION 'Invalid employee name' USING ERRCODE = '22023';
  END IF;
  IF p_canonical_phone IS NULL OR p_canonical_phone !~ '^\+[1-9][0-9]{7,14}$' THEN
    RAISE EXCEPTION 'Invalid employee phone' USING ERRCODE = '22023';
  END IF;
  IF p_invited_email IS NULL OR length(trim(p_invited_email)) > 254
     OR lower(trim(p_invited_email)) !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' THEN
    RAISE EXCEPTION 'Invalid employee email' USING ERRCODE = '22023';
  END IF;
  IF p_permissions IS NULL OR pg_catalog.jsonb_typeof(p_permissions) <> 'object' THEN
    RAISE EXCEPTION 'Invalid permissions' USING ERRCODE = '22023';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_catalog.jsonb_object_keys(p_permissions) AS k(key)
    WHERE k.key NOT IN (
      'CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE',
      'USE_COUPON','ISSUE_INVOICE','ISSUE_RECEIPT','REFUND','VOID',
      'CORRECT_AMOUNT','CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY',
      'MANAGE_PRODUCTS','VIEW_REPORTS'
    )
  ) OR EXISTS (
    SELECT 1 FROM pg_catalog.jsonb_each(p_permissions) AS e(key,value)
    WHERE pg_catalog.jsonb_typeof(e.value) <> 'boolean'
  ) THEN
    RAISE EXCEPTION 'Invalid staff permissions' USING ERRCODE = '22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.branches br
    WHERE br.id = p_branch_id AND br.business_id = p_business_id
  ) THEN
    RAISE EXCEPTION 'Invalid business or branch' USING ERRCODE = '22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.business_memberships bm
    WHERE bm.auth_user_id = p_created_by_auth_user_id
      AND bm.business_id = p_business_id
      AND bm.branch_id = p_branch_id
      AND bm.role = 'ADMIN'
      AND bm.is_active = true
  ) THEN
    RAISE EXCEPTION 'Admin authority required' USING ERRCODE = '42501';
  END IF;

  UPDATE private.staff_invites i
     SET revoked_at = pg_catalog.now()
   WHERE i.business_id = p_business_id
     AND i.branch_id = p_branch_id
     AND i.canonical_phone = p_canonical_phone
     AND i.used_at IS NULL
     AND i.revoked_at IS NULL
     AND i.expires_at > pg_catalog.now();

  v_expires_at := pg_catalog.now() + interval '48 hours';

  INSERT INTO private.staff_invites (
    token_hash, business_id, branch_id, display_name, canonical_phone,
    invited_email, role, permissions, created_by_auth_user_id,
    created_at, expires_at
  )
  VALUES (
    pg_catalog.lower(p_token_hash), p_business_id, p_branch_id,
    pg_catalog.btrim(p_display_name), p_canonical_phone,
    pg_catalog.lower(pg_catalog.btrim(p_invited_email)), 'CASHIER',
    p_permissions, p_created_by_auth_user_id, pg_catalog.now(), v_expires_at
  )
  RETURNING id INTO v_invite_id;

  RETURN QUERY SELECT v_invite_id, v_expires_at;
END;
$function$


CREATE OR REPLACE FUNCTION public.revoke_staff_invite_for_email(p_token_hash text, p_created_by_auth_user_id uuid)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
BEGIN
  IF p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$' OR p_created_by_auth_user_id IS NULL THEN
    RETURN false;
  END IF;
  UPDATE private.staff_invites
     SET revoked_at = COALESCE(revoked_at, pg_catalog.now())
   WHERE token_hash = pg_catalog.lower(p_token_hash)
     AND created_by_auth_user_id = p_created_by_auth_user_id
     AND used_at IS NULL;
  RETURN FOUND;
END;
$function$


CREATE OR REPLACE FUNCTION public.activate_staff_invite_for_auth(p_token_hash text, p_auth_user_id uuid, p_credential_hash text, p_credential_salt text, p_canonical_phone text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
  v_invite private.staff_invites%ROWTYPE;
  v_auth_email text;
  v_activation record;
  v_local_user_id text;
  v_existing_profile public.profiles%ROWTYPE;
BEGIN
  IF p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$'
     OR p_auth_user_id IS NULL
     OR p_credential_hash IS NULL
     OR p_credential_hash !~ '^pbkdf2-sha256:100000:[0-9a-f]{64}$'
     OR p_credential_salt IS NULL
     OR p_credential_salt !~ '^[0-9a-f]{32}$'
     OR p_canonical_phone IS NULL
     OR p_canonical_phone !~ '^\+[1-9][0-9]{7,14}$' THEN
    RETURN pg_catalog.jsonb_build_object('outcome','INVALID_REQUEST');
  END IF;

  SELECT pg_catalog.lower(pg_catalog.btrim(u.email))
    INTO v_auth_email
    FROM auth.users u
   WHERE u.id = p_auth_user_id
     AND u.email_confirmed_at IS NOT NULL;
  IF NOT FOUND OR v_auth_email IS NULL THEN
    RETURN pg_catalog.jsonb_build_object('outcome','EMAIL_NOT_VERIFIED');
  END IF;

  SELECT i.* INTO v_invite
    FROM private.staff_invites i
   WHERE i.token_hash = pg_catalog.lower(p_token_hash)
   FOR UPDATE;
  IF NOT FOUND OR v_invite.invited_email IS NULL
     OR pg_catalog.lower(v_invite.invited_email) <> v_auth_email THEN
    RETURN pg_catalog.jsonb_build_object('outcome','INVALID_INVITE');
  END IF;
  IF v_invite.canonical_phone <> p_canonical_phone THEN
    RETURN pg_catalog.jsonb_build_object('outcome','PHONE_MISMATCH');
  END IF;

  IF v_invite.used_at IS NULL AND
     (v_invite.revoked_at IS NOT NULL OR v_invite.expires_at <= pg_catalog.now()
       OR v_invite.role <> 'CASHIER') THEN
    RETURN pg_catalog.jsonb_build_object('outcome','INVALID_INVITE');
  END IF;

  SELECT p.* INTO v_existing_profile
    FROM public.profiles p
   WHERE p.auth_user_id = p_auth_user_id;
  IF FOUND AND v_existing_profile.canonical_phone IS NOT NULL
     AND v_existing_profile.canonical_phone <> v_invite.canonical_phone THEN
    RETURN pg_catalog.jsonb_build_object('outcome','PHONE_ALREADY_REGISTERED');
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.profiles p
    WHERE pg_catalog.lower(p.email) = v_auth_email
      AND p.auth_user_id <> p_auth_user_id
  ) THEN
    RETURN pg_catalog.jsonb_build_object('outcome','EMAIL_ALREADY_LINKED');
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.profiles p
    WHERE p.canonical_phone = v_invite.canonical_phone
      AND p.auth_user_id <> p_auth_user_id
  ) THEN
    RETURN pg_catalog.jsonb_build_object('outcome','PHONE_ALREADY_REGISTERED');
  END IF;

  IF v_invite.used_at IS NULL AND EXISTS (
    SELECT 1 FROM public.business_memberships bm
    WHERE bm.auth_user_id = p_auth_user_id
      AND bm.business_id = v_invite.business_id
      AND bm.branch_id = v_invite.branch_id
      AND bm.is_active = true
  ) THEN
    UPDATE private.staff_invites
       SET revoked_at = pg_catalog.now()
     WHERE id = v_invite.id AND used_at IS NULL;
    RETURN pg_catalog.jsonb_build_object('outcome','ALREADY_MEMBER');
  END IF;

  SELECT * INTO v_activation
    FROM public.activate_staff_invite(
      pg_catalog.lower(p_token_hash),
      p_credential_hash,
      p_credential_salt
    );

  IF v_activation.outcome = 'INVALID_INVITE' THEN
    RETURN pg_catalog.jsonb_build_object('outcome','INVALID_INVITE');
  ELSIF v_activation.outcome = 'PHONE_ALREADY_REGISTERED' THEN
    RETURN pg_catalog.jsonb_build_object('outcome','PHONE_ALREADY_REGISTERED');
  ELSIF v_activation.outcome NOT IN ('ACTIVATED','ALREADY_ACTIVATED') THEN
    RETURN pg_catalog.jsonb_build_object('outcome','ACTIVATION_FAILED');
  END IF;

  INSERT INTO public.profiles (
    auth_user_id, local_user_id, display_name, canonical_phone, email,
    language_code, is_active, created_at, updated_at
  )
  VALUES (
    p_auth_user_id, v_activation.local_user_id, v_activation.display_name,
    v_activation.canonical_phone, v_auth_email, 'en', true,
    pg_catalog.now(), pg_catalog.now()
  )
  ON CONFLICT (auth_user_id) DO UPDATE
    SET local_user_id = COALESCE(public.profiles.local_user_id, EXCLUDED.local_user_id),
        canonical_phone = COALESCE(public.profiles.canonical_phone, EXCLUDED.canonical_phone),
        email = COALESCE(public.profiles.email, EXCLUDED.email),
        updated_at = pg_catalog.now()
  RETURNING local_user_id INTO v_local_user_id;

  UPDATE public.business_memberships bm
     SET auth_user_id = p_auth_user_id,
         local_user_id = v_local_user_id,
         updated_at = pg_catalog.now()
   WHERE bm.id = v_activation.membership_id
     AND (bm.auth_user_id IS NULL OR bm.auth_user_id = p_auth_user_id);
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Unable to link activated membership to verified account'
      USING ERRCODE = 'P0001';
  END IF;

  RETURN pg_catalog.jsonb_build_object(
    'outcome',v_activation.outcome,
    'staff_identity_id',v_activation.staff_identity_id,
    'membership_id',v_activation.membership_id,
    'local_user_id',v_local_user_id,
    'business_id',v_activation.business_id,
    'business_name',v_activation.business_name,
    'branch_id',v_activation.branch_id,
    'branch_name',v_activation.branch_name,
    'branch_code',v_activation.branch_code,
    'country_code',v_activation.country_code,
    'currency_code',v_activation.currency_code,
    'currency_decimal_places',v_activation.currency_decimal_places,
    'currency_symbol',v_activation.currency_symbol,
    'timezone_id',v_activation.timezone_id,
    'tax_rounding_mode',v_activation.tax_rounding_mode,
    'default_tax_rate_bps',v_activation.default_tax_rate_bps,
    'branch_address',v_activation.branch_address,
    'branch_phone',v_activation.branch_phone,
    'tax_registration_number',v_activation.tax_registration_number,
    'display_name',v_activation.display_name,
    'canonical_phone',v_activation.canonical_phone,
    'role',v_activation.role,
    'permissions',v_activation.permissions,
    'activated_at',v_activation.activated_at
  );
END;
$function$


CREATE OR REPLACE FUNCTION public.preview_staff_invite(p_token_hash text)
 RETURNS TABLE(business_id text, business_name text, branch_id text, branch_name text, display_name text, canonical_phone text, role text, permissions jsonb, expires_at timestamp with time zone)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'private', 'pg_temp'
AS $function$
DECLARE
  v_auth_email text;
BEGIN
  IF p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}-- Edge Function, which sends the Supabase Auth email and stores the email.
DROP FUNCTION IF EXISTS public.create_staff_invite(text,text,text,text,text,jsonb);
REVOKE ALL ON FUNCTION public.preview_staff_invite(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.preview_staff_invite(text) TO authenticated, service_role;
REVOKE ALL ON FUNCTION public.activate_staff_invite(text,text,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.activate_staff_invite(text,text,text) TO service_role;

REVOKE ALL ON FUNCTION public.create_staff_invite_for_email(text,text,text,text,text,text,jsonb,uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.create_staff_invite_for_email(text,text,text,text,text,text,jsonb,uuid) TO service_role;
REVOKE ALL ON FUNCTION public.revoke_staff_invite_for_email(text,uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.revoke_staff_invite_for_email(text,uuid) TO service_role;
REVOKE ALL ON FUNCTION public.activate_staff_invite_for_auth(text,uuid,text,text,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.activate_staff_invite_for_auth(text,uuid,text,text,text) TO service_role;

REVOKE ALL ON FUNCTION public.check_auth_rate_limit(text,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.check_auth_rate_limit(text,text) TO service_role;
 THEN
    RETURN;
  END IF;

  SELECT lower(btrim(u.email))
    INTO v_auth_email
    FROM auth.users u
   WHERE u.id = auth.uid()
     AND u.email_confirmed_at IS NOT NULL;
  IF NOT FOUND OR v_auth_email IS NULL THEN
    RETURN;
  END IF;

  RETURN QUERY
  SELECT
    i.business_id,
    b.name,
    i.branch_id,
    br.name,
    i.display_name,
    i.canonical_phone,
    i.role,
    i.permissions,
    i.expires_at
  FROM private.staff_invites i
  JOIN public.businesses b ON b.id = i.business_id
  JOIN public.branches br ON br.id = i.branch_id AND br.business_id = i.business_id
  WHERE i.token_hash = lower(p_token_hash)
    AND lower(i.invited_email) = v_auth_email
    AND i.role = 'CASHIER'
    AND i.used_at IS NULL
    AND i.revoked_at IS NULL
    AND i.expires_at > now()
  LIMIT 1;
END;
$function$


-- The app creates invitations only through the authenticated staff-invite
-- Edge Function, which sends the Supabase Auth email and stores the email.
DROP FUNCTION IF EXISTS public.create_staff_invite(text,text,text,text,text,jsonb);
REVOKE ALL ON FUNCTION public.preview_staff_invite(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.preview_staff_invite(text) TO authenticated, service_role;
REVOKE ALL ON FUNCTION public.activate_staff_invite(text,text,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.activate_staff_invite(text,text,text) TO service_role;

REVOKE ALL ON FUNCTION public.create_staff_invite_for_email(text,text,text,text,text,text,jsonb,uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.create_staff_invite_for_email(text,text,text,text,text,text,jsonb,uuid) TO service_role;
REVOKE ALL ON FUNCTION public.revoke_staff_invite_for_email(text,uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.revoke_staff_invite_for_email(text,uuid) TO service_role;
REVOKE ALL ON FUNCTION public.activate_staff_invite_for_auth(text,uuid,text,text,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.activate_staff_invite_for_auth(text,uuid,text,text,text) TO service_role;

REVOKE ALL ON FUNCTION public.check_auth_rate_limit(text,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.check_auth_rate_limit(text,text) TO service_role;

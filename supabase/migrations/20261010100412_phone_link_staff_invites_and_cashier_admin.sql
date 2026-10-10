-- Phone-link staff invitations and remote cashier administration.
-- Existing financial and audit records are never physically deleted by cashier removal.

ALTER TABLE private.staff_invites
  ALTER COLUMN invited_email DROP NOT NULL;

ALTER TABLE public.business_memberships
  ADD COLUMN IF NOT EXISTS removed_at timestamptz;

CREATE TABLE IF NOT EXISTS private.staff_admin_audit_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_auth_user_id uuid NOT NULL,
  business_id text NOT NULL,
  branch_id text NOT NULL,
  membership_id text NOT NULL,
  event_type text NOT NULL CHECK (event_type IN ('PERMISSIONS_UPDATED', 'CASHIER_REMOVED')),
  before_state jsonb NOT NULL DEFAULT '{}'::jsonb,
  after_state jsonb NOT NULL DEFAULT '{}'::jsonb,
  occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_staff_admin_audit_membership_time
  ON private.staff_admin_audit_events (membership_id, occurred_at DESC);

REVOKE ALL ON TABLE private.staff_admin_audit_events FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.create_staff_invite_for_admin(
  p_token_hash text,
  p_business_id text,
  p_branch_id text,
  p_display_name text,
  p_canonical_phone text,
  p_permissions jsonb,
  p_created_by_auth_user_id uuid
)
RETURNS TABLE(invite_id uuid, expires_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path TO ''
AS $function$
DECLARE
  v_invite_id uuid;
  v_expires_at timestamptz;
BEGIN
  IF p_created_by_auth_user_id IS NULL
     OR p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$'
     OR p_business_id IS NULL OR btrim(p_business_id) = ''
     OR p_branch_id IS NULL OR btrim(p_branch_id) = ''
     OR p_display_name IS NULL
     OR length(btrim(p_display_name)) < 1 OR length(btrim(p_display_name)) > 100
     OR p_canonical_phone IS NULL OR p_canonical_phone !~ '^\+[1-9][0-9]{7,14}$'
     OR p_permissions IS NULL OR pg_catalog.jsonb_typeof(p_permissions) <> 'object' THEN
    RAISE EXCEPTION 'Invalid staff invitation request' USING ERRCODE = '22023';
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
    SELECT 1
    FROM public.business_memberships bm
    JOIN public.profiles p ON p.auth_user_id = bm.auth_user_id AND p.is_active = true
    JOIN public.businesses b ON b.id = bm.business_id AND b.is_active = true
    JOIN public.branches br ON br.id = bm.branch_id AND br.business_id = bm.business_id AND br.is_active = true
    WHERE bm.auth_user_id = p_created_by_auth_user_id
      AND bm.business_id = p_business_id AND bm.branch_id = p_branch_id
      AND bm.role = 'ADMIN' AND bm.is_active = true
      AND bm.removed_at IS NULL AND bm.staff_identity_id IS NULL
  ) THEN
    RAISE EXCEPTION 'Admin authority required for this branch' USING ERRCODE = '42501';
  END IF;

  UPDATE private.staff_invites i
     SET revoked_at = pg_catalog.now()
   WHERE i.business_id = p_business_id AND i.branch_id = p_branch_id
     AND i.canonical_phone = p_canonical_phone
     AND i.used_at IS NULL AND i.revoked_at IS NULL
     AND i.expires_at > pg_catalog.now();

  v_expires_at := pg_catalog.now() + interval '48 hours';

  INSERT INTO private.staff_invites (
    token_hash,business_id,branch_id,display_name,canonical_phone,
    invited_email,role,permissions,created_by_auth_user_id,created_at,expires_at
  )
  VALUES (
    pg_catalog.lower(p_token_hash),p_business_id,p_branch_id,
    pg_catalog.btrim(p_display_name),p_canonical_phone,
    NULL,'CASHIER',p_permissions,p_created_by_auth_user_id,pg_catalog.now(),v_expires_at
  )
  RETURNING id INTO v_invite_id;

  RETURN QUERY SELECT v_invite_id,v_expires_at;
END;
$function$;

REVOKE ALL ON FUNCTION public.create_staff_invite_for_admin(text,text,text,text,text,jsonb,uuid)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.create_staff_invite_for_admin(text,text,text,text,text,jsonb,uuid)
  TO service_role;

CREATE OR REPLACE FUNCTION public.preview_staff_invite(p_token_hash text)
RETURNS TABLE(
  business_id text,business_name text,branch_id text,branch_name text,
  display_name text,canonical_phone text,role text,permissions jsonb,expires_at timestamptz
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path TO ''
AS $function$
BEGIN
  IF p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$' THEN RETURN; END IF;

  RETURN QUERY
  SELECT i.business_id,b.name,i.branch_id,br.name,i.display_name,i.canonical_phone,
         i.role,i.permissions,i.expires_at
  FROM private.staff_invites i
  JOIN public.businesses b ON b.id = i.business_id AND b.is_active = true
  JOIN public.branches br ON br.id = i.branch_id
                         AND br.business_id = i.business_id AND br.is_active = true
  WHERE i.token_hash = pg_catalog.lower(p_token_hash)
    AND i.role = 'CASHIER' AND i.used_at IS NULL
    AND i.revoked_at IS NULL AND i.expires_at > pg_catalog.now()
  LIMIT 1;
END;
$function$;

REVOKE ALL ON FUNCTION public.preview_staff_invite(text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.preview_staff_invite(text) TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION public.list_admin_cashiers_with_permissions()
RETURNS TABLE(
  local_user_id text,membership_id text,branch_id text,display_name text,
  canonical_phone text,is_active boolean,permissions jsonb
)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path TO ''
AS $function$
DECLARE
  v_auth_user_id uuid := (SELECT auth.uid());
BEGIN
  IF v_auth_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;

  RETURN QUERY
  SELECT cashier.local_user_id,cashier.id,cashier.branch_id,staff.display_name,
         staff.canonical_phone,(cashier.is_active AND staff.is_active),
         pg_catalog.jsonb_build_object(
           'CREATE_SALE',cashier.can_create_sale,
           'ACCEPT_CASH',cashier.can_accept_cash,
           'ACCEPT_ONLINE',cashier.can_accept_online,
           'CREATE_CREDIT_SALE',cashier.can_create_credit_sale,
           'USE_COUPON',cashier.can_use_coupon,
           'ISSUE_INVOICE',cashier.can_issue_invoice,
           'ISSUE_RECEIPT',cashier.can_issue_receipt,
           'REFUND',cashier.can_refund,
           'VOID',cashier.can_void,
           'CORRECT_AMOUNT',cashier.can_correct_amount,
           'CHANGE_PAYMENT_METHOD',cashier.can_change_payment_method,
           'VIEW_BROAD_HISTORY',cashier.can_view_broad_history,
           'MANAGE_PRODUCTS',cashier.can_manage_products,
           'VIEW_REPORTS',cashier.can_view_reports
         )
  FROM public.business_memberships AS admin_membership
  JOIN public.profiles AS admin_profile
    ON admin_profile.auth_user_id = admin_membership.auth_user_id AND admin_profile.is_active = true
  JOIN public.businesses AS business
    ON business.id = admin_membership.business_id AND business.is_active = true
  JOIN public.branches AS branch
    ON branch.id = admin_membership.branch_id
   AND branch.business_id = admin_membership.business_id AND branch.is_active = true
  JOIN public.business_memberships AS cashier
    ON cashier.business_id = admin_membership.business_id
   AND cashier.branch_id = admin_membership.branch_id AND cashier.role = 'CASHIER'
  JOIN private.staff_identities AS staff ON staff.id = cashier.staff_identity_id
  WHERE admin_membership.auth_user_id = v_auth_user_id
    AND admin_membership.role = 'ADMIN' AND admin_membership.is_active = true
    AND admin_membership.removed_at IS NULL AND admin_membership.staff_identity_id IS NULL
    AND cashier.auth_user_id IS NULL AND cashier.staff_identity_id IS NOT NULL
    AND cashier.removed_at IS NULL AND cashier.local_user_id IS NOT NULL
    AND pg_catalog.btrim(cashier.local_user_id) <> ''
  ORDER BY staff.display_name,cashier.local_user_id;
END;
$function$;

REVOKE ALL ON FUNCTION public.list_admin_cashiers_with_permissions() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.list_admin_cashiers_with_permissions() TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.update_admin_cashier_permissions(
  p_membership_id text,p_permissions jsonb
)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path TO ''
AS $function$
DECLARE
  v_actor uuid := auth.uid();
  v_target public.business_memberships%ROWTYPE;
  v_before jsonb;
  v_after jsonb;
BEGIN
  IF v_actor IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501'; END IF;
  IF p_membership_id IS NULL OR btrim(p_membership_id) = ''
     OR p_permissions IS NULL OR pg_catalog.jsonb_typeof(p_permissions) <> 'object' THEN
    RAISE EXCEPTION 'Invalid permission update' USING ERRCODE = '22023';
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
  ) OR (SELECT count(*) FROM pg_catalog.jsonb_object_keys(p_permissions)) <> 14 THEN
    RAISE EXCEPTION 'Invalid staff permissions' USING ERRCODE = '22023';
  END IF;

  SELECT cashier.* INTO v_target
  FROM public.business_memberships cashier
  WHERE cashier.id = p_membership_id AND cashier.role = 'CASHIER'
    AND cashier.auth_user_id IS NULL AND cashier.staff_identity_id IS NOT NULL
    AND cashier.removed_at IS NULL
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Cashier not found' USING ERRCODE = 'P0002'; END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.business_memberships admin
    JOIN public.profiles profile
      ON profile.auth_user_id = admin.auth_user_id AND profile.is_active = true
    JOIN public.businesses business
      ON business.id = admin.business_id AND business.is_active = true
    JOIN public.branches branch
      ON branch.id = admin.branch_id
     AND branch.business_id = admin.business_id AND branch.is_active = true
    WHERE admin.auth_user_id = v_actor
      AND admin.business_id = v_target.business_id AND admin.branch_id = v_target.branch_id
      AND admin.role = 'ADMIN' AND admin.is_active = true
      AND admin.removed_at IS NULL AND admin.staff_identity_id IS NULL
  ) THEN
    RAISE EXCEPTION 'Admin authority required for this cashier' USING ERRCODE = '42501';
  END IF;

  v_before := pg_catalog.jsonb_build_object(
    'CREATE_SALE',v_target.can_create_sale,'ACCEPT_CASH',v_target.can_accept_cash,
    'ACCEPT_ONLINE',v_target.can_accept_online,'CREATE_CREDIT_SALE',v_target.can_create_credit_sale,
    'USE_COUPON',v_target.can_use_coupon,'ISSUE_INVOICE',v_target.can_issue_invoice,
    'ISSUE_RECEIPT',v_target.can_issue_receipt,'REFUND',v_target.can_refund,
    'VOID',v_target.can_void,'CORRECT_AMOUNT',v_target.can_correct_amount,
    'CHANGE_PAYMENT_METHOD',v_target.can_change_payment_method,
    'VIEW_BROAD_HISTORY',v_target.can_view_broad_history,
    'MANAGE_PRODUCTS',v_target.can_manage_products,'VIEW_REPORTS',v_target.can_view_reports
  );
  v_after := p_permissions;

  UPDATE public.business_memberships
  SET can_create_sale = (p_permissions->>'CREATE_SALE')::boolean,
      can_accept_cash = (p_permissions->>'ACCEPT_CASH')::boolean,
      can_accept_online = (p_permissions->>'ACCEPT_ONLINE')::boolean,
      can_create_credit_sale = (p_permissions->>'CREATE_CREDIT_SALE')::boolean,
      can_use_coupon = (p_permissions->>'USE_COUPON')::boolean,
      can_issue_invoice = (p_permissions->>'ISSUE_INVOICE')::boolean,
      can_issue_receipt = (p_permissions->>'ISSUE_RECEIPT')::boolean,
      can_refund = (p_permissions->>'REFUND')::boolean,
      can_void = (p_permissions->>'VOID')::boolean,
      can_correct_amount = (p_permissions->>'CORRECT_AMOUNT')::boolean,
      can_change_payment_method = (p_permissions->>'CHANGE_PAYMENT_METHOD')::boolean,
      can_view_broad_history = (p_permissions->>'VIEW_BROAD_HISTORY')::boolean,
      can_manage_products = (p_permissions->>'MANAGE_PRODUCTS')::boolean,
      can_view_reports = (p_permissions->>'VIEW_REPORTS')::boolean,
      updated_at = pg_catalog.now()
  WHERE id = v_target.id;

  UPDATE private.cashier_sync_sessions
     SET revoked_at = COALESCE(revoked_at, pg_catalog.now())
   WHERE membership_id = v_target.id AND revoked_at IS NULL;

  INSERT INTO private.staff_admin_audit_events (
    actor_auth_user_id,business_id,branch_id,membership_id,event_type,before_state,after_state,occurred_at
  ) VALUES (
    v_actor,v_target.business_id,v_target.branch_id,v_target.id,
    'PERMISSIONS_UPDATED',v_before,v_after,pg_catalog.now()
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.update_admin_cashier_permissions(text,jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.update_admin_cashier_permissions(text,jsonb) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.remove_admin_cashier(p_membership_id text)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path TO ''
AS $function$
DECLARE
  v_actor uuid := auth.uid();
  v_target public.business_memberships%ROWTYPE;
  v_now timestamptz := pg_catalog.now();
BEGIN
  IF v_actor IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501'; END IF;
  IF p_membership_id IS NULL OR btrim(p_membership_id) = '' THEN
    RAISE EXCEPTION 'Invalid cashier membership' USING ERRCODE = '22023';
  END IF;

  SELECT cashier.* INTO v_target
  FROM public.business_memberships cashier
  WHERE cashier.id = p_membership_id AND cashier.role = 'CASHIER'
    AND cashier.auth_user_id IS NULL AND cashier.staff_identity_id IS NOT NULL
    AND cashier.removed_at IS NULL
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Cashier not found or already removed' USING ERRCODE = 'P0002'; END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.business_memberships admin
    JOIN public.profiles profile
      ON profile.auth_user_id = admin.auth_user_id AND profile.is_active = true
    JOIN public.businesses business
      ON business.id = admin.business_id AND business.is_active = true
    JOIN public.branches branch
      ON branch.id = admin.branch_id
     AND branch.business_id = admin.business_id AND branch.is_active = true
    WHERE admin.auth_user_id = v_actor
      AND admin.business_id = v_target.business_id AND admin.branch_id = v_target.branch_id
      AND admin.role = 'ADMIN' AND admin.is_active = true
      AND admin.removed_at IS NULL AND admin.staff_identity_id IS NULL
  ) THEN
    RAISE EXCEPTION 'Admin authority required for this cashier' USING ERRCODE = '42501';
  END IF;

  UPDATE public.business_memberships
     SET is_active = false,removed_at = v_now,updated_at = v_now
   WHERE id = v_target.id;

  UPDATE private.cashier_sync_sessions
     SET revoked_at = COALESCE(revoked_at, v_now)
   WHERE membership_id = v_target.id AND revoked_at IS NULL;

  IF NOT EXISTS (
    SELECT 1 FROM public.business_memberships bm
    WHERE bm.staff_identity_id = v_target.staff_identity_id
      AND bm.is_active = true AND bm.removed_at IS NULL
  ) THEN
    UPDATE private.staff_identities
       SET is_active = false,updated_at = v_now
     WHERE id = v_target.staff_identity_id;
  END IF;

  INSERT INTO private.staff_admin_audit_events (
    actor_auth_user_id,business_id,branch_id,membership_id,event_type,before_state,after_state,occurred_at
  ) VALUES (
    v_actor,v_target.business_id,v_target.branch_id,v_target.id,
    'CASHIER_REMOVED',
    pg_catalog.jsonb_build_object('is_active',v_target.is_active,'removed_at',v_target.removed_at),
    pg_catalog.jsonb_build_object('is_active',false,'removed_at',v_now),
    v_now
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.remove_admin_cashier(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.remove_admin_cashier(text) TO authenticated, service_role;

DROP FUNCTION IF EXISTS public.activate_staff_invite_for_auth(text,uuid,text,text,text);
DROP FUNCTION IF EXISTS public.revoke_staff_invite_for_email(text,uuid);
DROP FUNCTION IF EXISTS public.create_staff_invite_for_email(text,text,text,text,text,text,jsonb,uuid);

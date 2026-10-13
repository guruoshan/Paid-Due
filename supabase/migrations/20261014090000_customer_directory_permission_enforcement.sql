CREATE OR REPLACE FUNCTION public.create_cashier_permission_request(p_membership_id text, p_permission_key text, p_note text DEFAULT NULL::text)
 RETURNS TABLE(request_id uuid, created_at timestamp with time zone, duplicate boolean)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
  v_member public.business_memberships%ROWTYPE;
  v_allowed boolean;
  v_id uuid;
  v_created timestamptz;
BEGIN
  IF p_membership_id IS NULL OR btrim(p_membership_id) = '' OR p_permission_key IS NULL OR
     p_permission_key NOT IN ('CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE',
       'USE_COUPON','ISSUE_INVOICE','REFUND','VOID','CORRECT_AMOUNT',
       'CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY','ISSUE_RECEIPT','MANAGE_PRODUCTS','VIEW_REPORTS') OR
     (p_note IS NOT NULL AND length(p_note) > 500) THEN
    RAISE EXCEPTION 'Invalid permission request' USING ERRCODE = '22023';
  END IF;
  SELECT m.* INTO v_member
  FROM public.business_memberships m
  JOIN private.staff_identities si ON si.id = m.staff_identity_id AND si.is_active = true
  JOIN public.businesses b ON b.id = m.business_id AND b.is_active = true
  JOIN public.branches br ON br.id = m.branch_id AND br.business_id = m.business_id AND br.is_active = true
  WHERE m.id = p_membership_id AND m.role = 'CASHIER' AND m.is_active = true AND m.removed_at IS NULL;
  IF NOT FOUND THEN RAISE EXCEPTION 'Active cashier membership required' USING ERRCODE = '42501'; END IF;
  v_allowed := CASE p_permission_key
    WHEN 'CREATE_SALE' THEN v_member.can_create_sale
    WHEN 'ACCEPT_CASH' THEN v_member.can_accept_cash
    WHEN 'ACCEPT_ONLINE' THEN v_member.can_accept_online
    WHEN 'CREATE_CREDIT_SALE' THEN v_member.can_create_credit_sale
    WHEN 'USE_COUPON' THEN v_member.can_use_coupon
    WHEN 'ISSUE_INVOICE' THEN v_member.can_issue_invoice
    WHEN 'ISSUE_RECEIPT' THEN v_member.can_issue_receipt
    WHEN 'REFUND' THEN v_member.can_refund
    WHEN 'VOID' THEN v_member.can_void
    WHEN 'CORRECT_AMOUNT' THEN v_member.can_correct_amount
    WHEN 'CHANGE_PAYMENT_METHOD' THEN v_member.can_change_payment_method
    WHEN 'VIEW_BROAD_HISTORY' THEN v_member.can_view_broad_history
    WHEN 'MANAGE_PRODUCTS' THEN v_member.can_manage_products
    WHEN 'VIEW_REPORTS' THEN v_member.can_view_reports

    ELSE false END;
  IF COALESCE(v_allowed, false) THEN RAISE EXCEPTION 'Permission is already granted' USING ERRCODE = '22023'; END IF;

  SELECT r.id, r.created_at INTO v_id, v_created FROM private.cashier_permission_requests r
    WHERE r.requester_membership_id = v_member.id AND r.permission_key = p_permission_key AND r.status = 'PENDING';
  IF v_id IS NOT NULL THEN RETURN QUERY SELECT v_id, v_created, true; RETURN; END IF;

  INSERT INTO private.cashier_permission_requests(business_id,branch_id,requester_membership_id,permission_key,note)
    VALUES(v_member.business_id,v_member.branch_id,v_member.id,p_permission_key,NULLIF(btrim(p_note),'' ))
    RETURNING id, private.cashier_permission_requests.created_at INTO v_id,v_created;

  INSERT INTO private.business_notifications(event_key,business_id,branch_id,recipient_membership_id,notification_type,title,body,payload)
  SELECT 'PERMISSION_REQUEST:' || v_id::text || ':' || admin.id, v_member.business_id, v_member.branch_id, admin.id,
    'PERMISSION_REQUEST','Cashier requested access',
    COALESCE(si.display_name,'A cashier') || ' requested ' || p_permission_key || ' permission.',
    jsonb_build_object('request_id',v_id,'requester_membership_id',v_member.id,'requester_name',COALESCE(si.display_name,'Cashier'),
      'permission_key',p_permission_key,'note',NULLIF(btrim(p_note),'' ))
  FROM public.business_memberships admin
  JOIN public.profiles profile ON profile.auth_user_id = admin.auth_user_id AND profile.is_active = true
  LEFT JOIN private.staff_identities si ON si.id = v_member.staff_identity_id
  WHERE admin.business_id = v_member.business_id AND admin.branch_id = v_member.branch_id
    AND admin.role = 'ADMIN' AND admin.is_active = true AND admin.removed_at IS NULL AND admin.staff_identity_id IS NULL
  ON CONFLICT(event_key) DO NOTHING;

  RETURN QUERY SELECT v_id,v_created,false;
END;
$function$;

CREATE OR REPLACE FUNCTION public.fetch_cashier_metadata(p_business_id text, p_branch_id text, p_membership_id text)
 RETURNS TABLE(membership_id text, business_id text, business_name text, branch_id text, branch_name text, branch_code text, branch_address text, branch_phone text, tax_registration_number text, country_code text, currency_code text, currency_decimal_places integer, currency_symbol text, timezone_id text, tax_rounding_mode text, default_tax_rate_bps integer, role text, permissions jsonb, display_name text, canonical_phone text)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
BEGIN
    RETURN QUERY
    SELECT
        m.id,
        b.id,
        b.name,
        br.id,
        br.name,
        br.code,
        br.address,
        br.phone,
        br.tax_registration_number,
        br.country_code,
        br.currency_code,
        br.currency_decimal_places,
        br.currency_symbol,
        br.timezone_id,
        br.tax_rounding_mode,
        br.default_tax_rate_bps,
        m.role,
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
            'can_change_payment_method',
                m.can_change_payment_method,
            'can_view_broad_history',
                m.can_view_broad_history,
            'can_manage_products', m.can_manage_products,
            'can_view_reports', m.can_view_reports
        ),
        si.display_name,
        si.canonical_phone
    FROM public.business_memberships m
    JOIN public.businesses b
      ON b.id = m.business_id
     AND b.is_active = true
    JOIN public.branches br
      ON br.id = m.branch_id
     AND br.business_id = m.business_id
     AND br.is_active = true
    JOIN private.staff_identities si
      ON si.id = m.staff_identity_id
     AND si.is_active = true
    WHERE m.id = p_membership_id
      AND m.business_id = p_business_id
      AND m.branch_id = p_branch_id
      AND m.role = 'CASHIER'
      AND m.is_active = true;
END;
$function$;

CREATE OR REPLACE FUNCTION public.list_admin_cashiers_with_permissions()
 RETURNS TABLE(local_user_id text, membership_id text, branch_id text, display_name text, canonical_phone text, is_active boolean, permissions jsonb, is_removed boolean)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
  v_auth_user_id uuid := (SELECT auth.uid());
BEGIN
  IF v_auth_user_id IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;

  RETURN QUERY
  SELECT
    cashier.local_user_id,
    cashier.id,
    cashier.branch_id,
    staff.display_name,
    staff.canonical_phone,
    (cashier.is_active AND staff.is_active AND cashier.removed_at IS NULL),
    pg_catalog.jsonb_build_object(
      'CREATE_SALE', cashier.can_create_sale,
      'ACCEPT_CASH', cashier.can_accept_cash,
      'ACCEPT_ONLINE', cashier.can_accept_online,
      'CREATE_CREDIT_SALE', cashier.can_create_credit_sale,
      'USE_COUPON', cashier.can_use_coupon,
      'ISSUE_INVOICE', cashier.can_issue_invoice,
      'ISSUE_RECEIPT', cashier.can_issue_receipt,
      'REFUND', cashier.can_refund,
      'VOID', cashier.can_void,
      'CORRECT_AMOUNT', cashier.can_correct_amount,
      'CHANGE_PAYMENT_METHOD', cashier.can_change_payment_method,
      'VIEW_BROAD_HISTORY', cashier.can_view_broad_history,
      'MANAGE_PRODUCTS', cashier.can_manage_products,
      'VIEW_REPORTS', cashier.can_view_reports
    ),
    (cashier.removed_at IS NOT NULL)
  FROM public.business_memberships AS admin_membership
  JOIN public.profiles AS admin_profile
    ON admin_profile.auth_user_id = admin_membership.auth_user_id
   AND admin_profile.is_active = true
  JOIN public.businesses AS business
    ON business.id = admin_membership.business_id
   AND business.is_active = true
  JOIN public.branches AS branch
    ON branch.id = admin_membership.branch_id
   AND branch.business_id = admin_membership.business_id
   AND branch.is_active = true
  JOIN public.business_memberships AS cashier
    ON cashier.business_id = admin_membership.business_id
   AND cashier.branch_id = admin_membership.branch_id
   AND cashier.role = 'CASHIER'
  JOIN private.staff_identities AS staff
    ON staff.id = cashier.staff_identity_id
  WHERE admin_membership.auth_user_id = v_auth_user_id
    AND admin_membership.role = 'ADMIN'
    AND admin_membership.is_active = true
    AND admin_membership.removed_at IS NULL
    AND admin_membership.staff_identity_id IS NULL
    AND cashier.staff_identity_id IS NOT NULL
    AND cashier.local_user_id IS NOT NULL
    AND pg_catalog.btrim(cashier.local_user_id) <> ''
  ORDER BY staff.display_name, cashier.local_user_id;
END;
$function$;

CREATE OR REPLACE FUNCTION public.resolve_admin_permission_request(p_request_id uuid, p_decision text, p_additional_permissions jsonb DEFAULT '{}'::jsonb)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
  v_actor uuid := auth.uid();
  v_req private.cashier_permission_requests%ROWTYPE;
  v_target public.business_memberships%ROWTYPE;
  v_admin public.business_memberships%ROWTYPE;
  v_before jsonb;
  v_after jsonb;
  v_key text;
BEGIN
  IF v_actor IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501'; END IF;
  p_additional_permissions := COALESCE(p_additional_permissions, '{}'::jsonb);
  IF p_decision IS NULL OR p_decision NOT IN ('APPROVE','REJECT')
     OR jsonb_typeof(p_additional_permissions) IS DISTINCT FROM 'object' THEN
    RAISE EXCEPTION 'Invalid permission request decision' USING ERRCODE = '22023';
  END IF;
  IF EXISTS (
    SELECT 1 FROM jsonb_each(p_additional_permissions) e
    WHERE e.key NOT IN ('CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE',
      'USE_COUPON','ISSUE_INVOICE','REFUND','VOID','CORRECT_AMOUNT',
      'CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY','ISSUE_RECEIPT','MANAGE_PRODUCTS','VIEW_REPORTS')
      OR jsonb_typeof(e.value) <> 'boolean' OR e.value <> 'true'::jsonb
  ) THEN RAISE EXCEPTION 'Additional permissions must be known permissions set to true' USING ERRCODE = '22023'; END IF;

  SELECT * INTO v_req FROM private.cashier_permission_requests WHERE id = p_request_id FOR UPDATE;
  IF NOT FOUND OR v_req.status <> 'PENDING' THEN RAISE EXCEPTION 'Permission request not pending' USING ERRCODE = 'P0002'; END IF;
  SELECT m.* INTO v_admin FROM public.business_memberships m
  JOIN public.profiles profile ON profile.auth_user_id = m.auth_user_id AND profile.is_active = true
  JOIN public.businesses business ON business.id = m.business_id AND business.is_active = true
  JOIN public.branches branch ON branch.id = m.branch_id AND branch.business_id = m.business_id AND branch.is_active = true
  WHERE m.auth_user_id = v_actor AND m.business_id = v_req.business_id AND m.branch_id = v_req.branch_id
    AND m.role = 'ADMIN' AND m.is_active = true AND m.removed_at IS NULL AND m.staff_identity_id IS NULL;
  IF NOT FOUND THEN RAISE EXCEPTION 'Admin authority required' USING ERRCODE = '42501'; END IF;
  SELECT m.* INTO v_target
  FROM public.business_memberships m
  JOIN private.staff_identities si ON si.id = m.staff_identity_id AND si.is_active = true
  WHERE m.id = v_req.requester_membership_id
  FOR UPDATE OF m;
  IF NOT FOUND OR v_target.role <> 'CASHIER' OR NOT v_target.is_active OR v_target.removed_at IS NOT NULL
     OR v_target.business_id <> v_req.business_id OR v_target.branch_id <> v_req.branch_id
     OR v_target.staff_identity_id IS NULL THEN
    RAISE EXCEPTION 'Cashier is no longer active' USING ERRCODE = 'P0002';
  END IF;

  IF p_decision = 'APPROVE' THEN
    v_before := jsonb_build_object(
      'CREATE_SALE',v_target.can_create_sale,'ACCEPT_CASH',v_target.can_accept_cash,
      'ACCEPT_ONLINE',v_target.can_accept_online,'CREATE_CREDIT_SALE',v_target.can_create_credit_sale,
      'USE_COUPON',v_target.can_use_coupon,'ISSUE_INVOICE',v_target.can_issue_invoice,
      'ISSUE_RECEIPT',v_target.can_issue_receipt,'REFUND',v_target.can_refund,'VOID',v_target.can_void,
      'CORRECT_AMOUNT',v_target.can_correct_amount,'CHANGE_PAYMENT_METHOD',v_target.can_change_payment_method,
      'VIEW_BROAD_HISTORY',v_target.can_view_broad_history,'MANAGE_PRODUCTS',v_target.can_manage_products,
      'VIEW_REPORTS',v_target.can_view_reports
    );
    v_after := v_before || jsonb_build_object(v_req.permission_key, true) || p_additional_permissions;
    PERFORM set_config('app.skip_permissions_notification', 'true', true);
    UPDATE public.business_memberships SET
      can_create_sale = COALESCE((v_after->>'CREATE_SALE')::boolean, can_create_sale),
      can_accept_cash = COALESCE((v_after->>'ACCEPT_CASH')::boolean, can_accept_cash),
      can_accept_online = COALESCE((v_after->>'ACCEPT_ONLINE')::boolean, can_accept_online),
      can_create_credit_sale = COALESCE((v_after->>'CREATE_CREDIT_SALE')::boolean, can_create_credit_sale),
      can_use_coupon = COALESCE((v_after->>'USE_COUPON')::boolean, can_use_coupon),
      can_issue_invoice = COALESCE((v_after->>'ISSUE_INVOICE')::boolean, can_issue_invoice),
      can_issue_receipt = COALESCE((v_after->>'ISSUE_RECEIPT')::boolean, can_issue_receipt),
      can_refund = COALESCE((v_after->>'REFUND')::boolean, can_refund),
      can_void = COALESCE((v_after->>'VOID')::boolean, can_void),
      can_correct_amount = COALESCE((v_after->>'CORRECT_AMOUNT')::boolean, can_correct_amount),
      can_change_payment_method = COALESCE((v_after->>'CHANGE_PAYMENT_METHOD')::boolean, can_change_payment_method),
      can_view_broad_history = COALESCE((v_after->>'VIEW_BROAD_HISTORY')::boolean, can_view_broad_history),
      can_manage_products = COALESCE((v_after->>'MANAGE_PRODUCTS')::boolean, can_manage_products),
      can_view_reports = COALESCE((v_after->>'VIEW_REPORTS')::boolean, can_view_reports),

      updated_at = now()
    WHERE id = v_target.id;
    INSERT INTO private.staff_admin_audit_events(
      actor_auth_user_id,business_id,branch_id,membership_id,event_type,before_state,after_state,occurred_at
    ) VALUES(v_actor,v_req.business_id,v_req.branch_id,v_target.id,'PERMISSIONS_UPDATED',v_before,v_after,now());
  END IF;

  UPDATE private.cashier_permission_requests SET
    status = CASE WHEN p_decision = 'APPROVE' THEN 'APPROVED' ELSE 'REJECTED' END,
    resolved_at = now(), resolved_by_auth_user_id = v_actor,
    granted_permissions = CASE WHEN p_decision = 'APPROVE' THEN jsonb_build_object(v_req.permission_key, true) || p_additional_permissions ELSE '{}'::jsonb END
  WHERE id = v_req.id;

  PERFORM private.enqueue_business_notification(
    'PERMISSION_REQUEST_RESULT:' || v_req.id::text,
    v_req.business_id, v_req.branch_id, v_req.requester_membership_id,
    'PERMISSION_REQUEST_RESULT',
    CASE WHEN p_decision = 'APPROVE' THEN 'Permission request approved' ELSE 'Permission request declined' END,
    CASE WHEN p_decision = 'APPROVE'
      THEN 'Your request for ' || v_req.permission_key || ' was approved. Reopen or refresh the app to load your updated permissions.'
      ELSE 'Your request for ' || v_req.permission_key || ' was declined. Your existing permissions are unchanged.'
    END,
    jsonb_build_object('request_id', v_req.id, 'decision', p_decision,
      'requested_permission', v_req.permission_key,
      'additional_permissions', CASE WHEN p_decision = 'APPROVE' THEN p_additional_permissions ELSE '{}'::jsonb END)
  );
END;
$function$;

CREATE OR REPLACE FUNCTION public.sync_customer_directory_list(p_business_id text, p_branch_id text, p_membership_id text, p_after_updated_at timestamp with time zone DEFAULT NULL::timestamp with time zone, p_limit integer DEFAULT 100)
 RETURNS TABLE(customer_id text, canonical_phone text, display_name text, created_at timestamp with time zone, updated_at timestamp with time zone)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
begin
  if p_business_id is null or p_branch_id is null or p_membership_id is null
     or p_limit is null or p_limit < 1 or p_limit > 500 then
    raise exception 'INVALID_CUSTOMER_SYNC_REQUEST' using errcode = '22023';
  end if;

  if not exists (
    select 1
    from public.business_memberships m
    join public.businesses b on b.id = m.business_id and b.is_active = true
    join public.branches br on br.id = m.branch_id and br.business_id = m.business_id and br.is_active = true
    where m.id = p_membership_id and m.business_id = p_business_id
      and m.branch_id = p_branch_id and m.is_active = true and m.removed_at is null
      and (m.role = 'ADMIN' or (m.role = 'CASHIER' and m.can_view_broad_history = true))
  ) then
    raise exception 'ACTIVE_MEMBERSHIP_REQUIRED' using errcode = '42501';
  end if;

  return query
  select d.customer_id, d.canonical_phone, d.display_name, d.created_at, d.updated_at
  from private.customer_directory d
  where d.business_id = p_business_id
    and (p_after_updated_at is null or d.updated_at >= p_after_updated_at)
  order by d.updated_at asc, d.canonical_phone asc
  limit p_limit;
end;
$function$;

CREATE OR REPLACE FUNCTION public.sync_customer_directory_upsert(p_business_id text, p_branch_id text, p_membership_id text, p_customer_id text, p_canonical_phone text, p_display_name text, p_created_at timestamp with time zone DEFAULT now())
 RETURNS TABLE(customer_id text, canonical_phone text, display_name text, created_at timestamp with time zone, updated_at timestamp with time zone, was_created boolean)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_membership public.business_memberships%rowtype;
  v_row private.customer_directory%rowtype;
  v_inserted integer := 0;
begin
  if p_business_id is null or p_branch_id is null or p_membership_id is null
     or p_customer_id is null or length(btrim(p_customer_id)) not between 1 and 128
     or p_canonical_phone is null or p_canonical_phone !~ '^\+[1-9][0-9]{7,14}$'
     or p_display_name is null or length(btrim(p_display_name)) not between 1 and 200 then
    raise exception 'INVALID_CUSTOMER' using errcode = '22023';
  end if;

  select m.* into v_membership
  from public.business_memberships m
  join public.businesses b on b.id = m.business_id and b.is_active = true
  join public.branches br on br.id = m.branch_id and br.business_id = m.business_id and br.is_active = true
  where m.id = p_membership_id
    and m.business_id = p_business_id
    and m.branch_id = p_branch_id
    and m.is_active = true
    and m.removed_at is null
    and (m.role = 'ADMIN' or (m.role = 'CASHIER' and m.can_view_broad_history = true));
  if not found then
    raise exception 'ACTIVE_MEMBERSHIP_REQUIRED' using errcode = '42501';
  end if;

  insert into private.customer_directory (
    business_id, canonical_phone, customer_id, display_name,
    created_at, updated_at, created_by_membership_id, updated_by_membership_id
  ) values (
    p_business_id, p_canonical_phone, p_customer_id, btrim(p_display_name),
    coalesce(p_created_at, now()), now(), p_membership_id, p_membership_id
  )
  on conflict on constraint customer_directory_pkey do nothing;

  get diagnostics v_inserted = row_count;
  select d.* into v_row
  from private.customer_directory d
  where d.business_id = p_business_id and d.canonical_phone = p_canonical_phone
  for update;

  -- Identity is phone-based within a business. Do not silently rename an existing
  -- customer or replace its canonical ID when another device has a different local ID.
  return query select v_row.customer_id, v_row.canonical_phone, v_row.display_name,
    v_row.created_at, v_row.updated_at, (v_inserted > 0);
end;
$function$;

CREATE OR REPLACE FUNCTION public.update_admin_cashier_permissions(p_membership_id text, p_permissions jsonb)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
  v_actor uuid := auth.uid();
  v_target public.business_memberships%ROWTYPE;
  v_before jsonb;
  v_after jsonb;
BEGIN
  IF v_actor IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;
  IF p_membership_id IS NULL OR btrim(p_membership_id) = ''
     OR p_permissions IS NULL
     OR pg_catalog.jsonb_typeof(p_permissions) <> 'object' THEN
    RAISE EXCEPTION 'Invalid permission update' USING ERRCODE = '22023';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM pg_catalog.jsonb_object_keys(p_permissions) AS k(key)
    WHERE k.key NOT IN (
      'CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE',
      'USE_COUPON','ISSUE_INVOICE','ISSUE_RECEIPT','REFUND','VOID',
      'CORRECT_AMOUNT','CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY',
      'MANAGE_PRODUCTS','VIEW_REPORTS'
    )
  ) OR EXISTS (
    SELECT 1
    FROM pg_catalog.jsonb_each(p_permissions) AS e(key, value)
    WHERE pg_catalog.jsonb_typeof(e.value) <> 'boolean'
  ) OR (
    SELECT count(*) FROM pg_catalog.jsonb_object_keys(p_permissions)
  ) <> 14 THEN
    RAISE EXCEPTION 'Invalid staff permissions' USING ERRCODE = '22023';
  END IF;

  SELECT cashier.* INTO v_target
  FROM public.business_memberships cashier
  WHERE cashier.id = p_membership_id
    AND cashier.role = 'CASHIER'
    AND cashier.staff_identity_id IS NOT NULL
    AND cashier.removed_at IS NULL
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Cashier not found' USING ERRCODE = 'P0002';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.business_memberships admin
    JOIN public.profiles profile
      ON profile.auth_user_id = admin.auth_user_id
     AND profile.is_active = true
    JOIN public.businesses business
      ON business.id = admin.business_id AND business.is_active = true
    JOIN public.branches branch
      ON branch.id = admin.branch_id
     AND branch.business_id = admin.business_id
     AND branch.is_active = true
    WHERE admin.auth_user_id = v_actor
      AND admin.business_id = v_target.business_id
      AND admin.branch_id = v_target.branch_id
      AND admin.role = 'ADMIN'
      AND admin.is_active = true
      AND admin.removed_at IS NULL
      AND admin.staff_identity_id IS NULL
  ) THEN
    RAISE EXCEPTION 'Admin authority required for this cashier' USING ERRCODE = '42501';
  END IF;

  v_before := pg_catalog.jsonb_build_object(
    'CREATE_SALE',v_target.can_create_sale,
    'ACCEPT_CASH',v_target.can_accept_cash,
    'ACCEPT_ONLINE',v_target.can_accept_online,
    'CREATE_CREDIT_SALE',v_target.can_create_credit_sale,
    'USE_COUPON',v_target.can_use_coupon,
    'ISSUE_INVOICE',v_target.can_issue_invoice,
    'ISSUE_RECEIPT',v_target.can_issue_receipt,
    'REFUND',v_target.can_refund,
    'VOID',v_target.can_void,
    'CORRECT_AMOUNT',v_target.can_correct_amount,
    'CHANGE_PAYMENT_METHOD',v_target.can_change_payment_method,
    'VIEW_BROAD_HISTORY',v_target.can_view_broad_history,
    'MANAGE_PRODUCTS',v_target.can_manage_products,
    'VIEW_REPORTS',v_target.can_view_reports
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

  INSERT INTO private.staff_admin_audit_events (
    actor_auth_user_id, business_id, branch_id, membership_id,
    event_type, before_state, after_state, occurred_at
  ) VALUES (
    v_actor, v_target.business_id, v_target.branch_id, v_target.id,
    'PERMISSIONS_UPDATED', v_before, v_after, pg_catalog.now()
  );
END;
$function$;

CREATE OR REPLACE FUNCTION public.update_cashier_permissions(p_membership_id text, p_permissions jsonb)
 RETURNS TABLE(membership_id text, updated_at timestamp with time zone)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
  v_uid uuid := (SELECT auth.uid());
  v_target public.business_memberships%ROWTYPE;
  v_updated timestamptz;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
  END IF;
  IF p_membership_id IS NULL OR btrim(p_membership_id) = ''
     OR p_permissions IS NULL
     OR pg_catalog.jsonb_typeof(p_permissions) <> 'object' THEN
    RAISE EXCEPTION 'Invalid permissions update' USING ERRCODE = '22023';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_catalog.jsonb_object_keys(p_permissions) AS k(key)
    WHERE k.key NOT IN (
      'CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE','USE_COUPON',
      'ISSUE_INVOICE','ISSUE_RECEIPT','REFUND','VOID','CORRECT_AMOUNT',
      'CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY','MANAGE_PRODUCTS','VIEW_REPORTS'
    )
  ) OR EXISTS (
    SELECT 1 FROM pg_catalog.jsonb_each(p_permissions) AS e(key,value)
    WHERE pg_catalog.jsonb_typeof(e.value) <> 'boolean'
  ) THEN
    RAISE EXCEPTION 'Unknown permission or non-boolean value' USING ERRCODE = '22023';
  END IF;

  SELECT m.* INTO v_target
  FROM public.business_memberships m
  JOIN private.staff_identities si ON si.id = m.staff_identity_id AND si.is_active = true
  JOIN public.businesses b ON b.id = m.business_id AND b.is_active = true
  JOIN public.branches br ON br.id = m.branch_id AND br.business_id = m.business_id AND br.is_active = true
  WHERE m.id = p_membership_id
    AND m.role = 'CASHIER'
    AND m.is_active = true
    AND m.removed_at IS NULL
    AND m.staff_identity_id IS NOT NULL
  FOR UPDATE OF m;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Active cashier membership not found' USING ERRCODE = 'P0002';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.business_memberships a
    JOIN public.profiles p ON p.auth_user_id = a.auth_user_id AND p.is_active = true
    JOIN public.businesses b ON b.id = a.business_id AND b.is_active = true
    JOIN public.branches br ON br.id = a.branch_id AND br.business_id = a.business_id AND br.is_active = true
    WHERE a.auth_user_id = v_uid
      AND a.business_id = v_target.business_id
      AND a.branch_id = v_target.branch_id
      AND a.role = 'ADMIN'
      AND a.is_active = true
      AND a.removed_at IS NULL
      AND a.staff_identity_id IS NULL
  ) THEN
    RAISE EXCEPTION 'Active Admin authority required for this branch' USING ERRCODE = '42501';
  END IF;

  UPDATE public.business_memberships m SET
    can_create_sale = COALESCE((p_permissions->>'CREATE_SALE')::boolean,m.can_create_sale),
    can_accept_cash = COALESCE((p_permissions->>'ACCEPT_CASH')::boolean,m.can_accept_cash),
    can_accept_online = COALESCE((p_permissions->>'ACCEPT_ONLINE')::boolean,m.can_accept_online),
    can_create_credit_sale = COALESCE((p_permissions->>'CREATE_CREDIT_SALE')::boolean,m.can_create_credit_sale),
    can_use_coupon = COALESCE((p_permissions->>'USE_COUPON')::boolean,m.can_use_coupon),
    can_issue_invoice = COALESCE((p_permissions->>'ISSUE_INVOICE')::boolean,m.can_issue_invoice),
    can_issue_receipt = COALESCE((p_permissions->>'ISSUE_RECEIPT')::boolean,m.can_issue_receipt),
    can_refund = COALESCE((p_permissions->>'REFUND')::boolean,m.can_refund),
    can_void = COALESCE((p_permissions->>'VOID')::boolean,m.can_void),
    can_correct_amount = COALESCE((p_permissions->>'CORRECT_AMOUNT')::boolean,m.can_correct_amount),
    can_change_payment_method = COALESCE((p_permissions->>'CHANGE_PAYMENT_METHOD')::boolean,m.can_change_payment_method),
    can_view_broad_history = COALESCE((p_permissions->>'VIEW_BROAD_HISTORY')::boolean,m.can_view_broad_history),
    can_manage_products = COALESCE((p_permissions->>'MANAGE_PRODUCTS')::boolean,m.can_manage_products),
    can_view_reports = COALESCE((p_permissions->>'VIEW_REPORTS')::boolean,m.can_view_reports),
    updated_at = pg_catalog.now()
  WHERE m.id = v_target.id
  RETURNING m.updated_at INTO v_updated;

  RETURN QUERY SELECT v_target.id, v_updated;
END;
$function$;


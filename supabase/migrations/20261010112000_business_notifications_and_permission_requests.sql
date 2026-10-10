-- Durable Paid&Due notifications and cashier permission requests.
-- Notifications are an inbox projection, never a source of financial truth.
CREATE TABLE IF NOT EXISTS private.business_notifications (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_key text NOT NULL UNIQUE,
  business_id text NOT NULL REFERENCES public.businesses(id) ON DELETE RESTRICT,
  branch_id text NOT NULL REFERENCES public.branches(id) ON DELETE RESTRICT,
  recipient_membership_id text NOT NULL REFERENCES public.business_memberships(id) ON DELETE RESTRICT,
  notification_type text NOT NULL CHECK (notification_type IN (
    'DAY_CLOSED', 'PERMISSIONS_CHANGED', 'PERMISSION_REQUEST',
    'PERMISSION_REQUEST_RESULT', 'CASH_DISCREPANCY'
  )),
  title text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 200),
  body text NOT NULL CHECK (length(btrim(body)) BETWEEN 1 AND 1000),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload) = 'object'),
  created_at timestamptz NOT NULL DEFAULT now(),
  read_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_business_notifications_recipient_unread
  ON private.business_notifications (recipient_membership_id, created_at DESC)
  WHERE read_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_business_notifications_recipient_history
  ON private.business_notifications (recipient_membership_id, created_at DESC);

CREATE TABLE IF NOT EXISTS private.cashier_permission_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  business_id text NOT NULL REFERENCES public.businesses(id) ON DELETE RESTRICT,
  branch_id text NOT NULL REFERENCES public.branches(id) ON DELETE RESTRICT,
  requester_membership_id text NOT NULL REFERENCES public.business_memberships(id) ON DELETE RESTRICT,
  permission_key text NOT NULL CHECK (permission_key IN (
    'CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE',
    'USE_COUPON','ISSUE_INVOICE','REFUND','VOID',
    'CORRECT_AMOUNT','CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY'
  )),
  note text,
  status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  resolved_at timestamptz,
  resolved_by_auth_user_id uuid REFERENCES auth.users(id) ON DELETE SET NULL,
  granted_permissions jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(granted_permissions) = 'object')
);
CREATE INDEX IF NOT EXISTS idx_cashier_permission_requests_branch_pending
  ON private.cashier_permission_requests (business_id, branch_id, created_at DESC)
  WHERE status = 'PENDING';
CREATE UNIQUE INDEX IF NOT EXISTS idx_cashier_permission_requests_one_pending_key
  ON private.cashier_permission_requests (requester_membership_id, permission_key)
  WHERE status = 'PENDING';

ALTER TABLE private.business_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE private.cashier_permission_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE private.business_notifications FROM PUBLIC, anon, authenticated;
REVOKE ALL ON TABLE private.cashier_permission_requests FROM PUBLIC, anon, authenticated;

-- Helper used only by trusted database triggers/functions. Idempotency is enforced
-- by event_key, so a retried financial envelope cannot create duplicate alerts.
CREATE OR REPLACE FUNCTION private.enqueue_business_notification(
  p_event_key text,
  p_business_id text,
  p_branch_id text,
  p_recipient_membership_id text,
  p_type text,
  p_title text,
  p_body text,
  p_payload jsonb DEFAULT '{}'::jsonb
) RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path TO ''
AS $function$
  INSERT INTO private.business_notifications (
    event_key, business_id, branch_id, recipient_membership_id,
    notification_type, title, body, payload
  )
  VALUES (
    p_event_key, p_business_id, p_branch_id, p_recipient_membership_id,
    p_type, p_title, p_body, COALESCE(p_payload, '{}'::jsonb)
  )
  ON CONFLICT (event_key) DO NOTHING;
$function$;
REVOKE ALL ON FUNCTION private.enqueue_business_notification(text,text,text,text,text,text,text,jsonb) FROM PUBLIC, anon, authenticated;

-- Produce the Admin notification only after a close operation is accepted into
-- the authoritative envelope store. The details are a read-only report snapshot
-- from the close command; financial ledgers remain authoritative.
CREATE OR REPLACE FUNCTION private.notify_admins_of_confirmed_day_close()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_actor_name text;
  v_report jsonb;
  v_body text;
  v_admin record;
BEGIN
  IF NEW.operation_type <> 'CLOSE_CASH_SHIFT' THEN
    RETURN NEW;
  END IF;

  v_report := NEW.canonical_payload -> 'day_closing_report';
  SELECT COALESCE(si.display_name, p.display_name, NEW.canonical_payload #>> '{operation,actor_user_id}', 'Cashier')
    INTO v_actor_name
  FROM public.business_memberships bm
  LEFT JOIN private.staff_identities si ON si.id = bm.staff_identity_id
  LEFT JOIN public.profiles p ON p.auth_user_id = bm.auth_user_id
  WHERE bm.id = NEW.actor_membership_id;

  v_body := CASE
    WHEN v_report IS NULL OR jsonb_typeof(v_report) <> 'object'
      THEN COALESCE(v_actor_name, 'Cashier') || ' closed a shift. Open the closing record for available details.'
    ELSE COALESCE(v_actor_name, 'Cashier') || ' closed a shift. Review sales, payment methods, and cash reconciliation.'
  END;

  FOR v_admin IN
    SELECT admin.id AS membership_id
    FROM public.business_memberships admin
    JOIN public.profiles profile ON profile.auth_user_id = admin.auth_user_id AND profile.is_active = true
    WHERE admin.business_id = NEW.business_id
      AND admin.branch_id = NEW.branch_id
      AND admin.role = 'ADMIN'
      AND admin.is_active = true
      AND admin.removed_at IS NULL
      AND admin.staff_identity_id IS NULL
  LOOP
    PERFORM private.enqueue_business_notification(
      'DAY_CLOSED:' || NEW.business_id || ':' || NEW.operation_id || ':' || v_admin.membership_id,
      NEW.business_id,
      NEW.branch_id,
      v_admin.membership_id,
      'DAY_CLOSED',
      'Cashier day closing completed',
      v_body,
      jsonb_build_object(
        'operation_id', NEW.operation_id,
        'cashier_membership_id', NEW.actor_membership_id,
        'cashier_name', COALESCE(v_actor_name, 'Cashier'),
        'closed_at', NEW.occurred_at,
        'received_at', NEW.received_at,
        'branch_timezone_id', NEW.branch_timezone_id,
        'business_local_date', NEW.business_local_date,
        'report', COALESCE(v_report, '{}'::jsonb)
      )
    );
    IF (CASE
      WHEN COALESCE(v_report ->> 'difference_minor', '0') ~ '^-?[0-9]+$'
      THEN COALESCE(v_report ->> 'difference_minor', '0')::numeric <> 0
      ELSE false
    END) THEN
      PERFORM private.enqueue_business_notification(
        'CASH_DIFFERENCE:' || NEW.business_id || ':' || NEW.operation_id || ':' || v_admin.membership_id,
        NEW.business_id,
        NEW.branch_id,
        v_admin.membership_id,
        'CASH_DISCREPANCY',
        'Cash difference needs review',
        COALESCE(v_actor_name, 'Cashier') || ' closed a shift with a cash difference. Review the closing report.',
        jsonb_build_object(
          'operation_id', NEW.operation_id,
          'cashier_name', COALESCE(v_actor_name, 'Cashier'),
          'report', COALESCE(v_report, '{}'::jsonb)
        )
      );
    END IF;
  END LOOP;
  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION private.notify_admins_of_confirmed_day_close() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS trg_notify_admins_of_confirmed_day_close ON private.financial_operation_envelopes;
CREATE TRIGGER trg_notify_admins_of_confirmed_day_close
AFTER INSERT ON private.financial_operation_envelopes
FOR EACH ROW
WHEN (NEW.operation_type = 'CLOSE_CASH_SHIFT')
EXECUTE FUNCTION private.notify_admins_of_confirmed_day_close();

-- Alert the affected cashier after a server-confirmed permission update.
CREATE OR REPLACE FUNCTION private.notify_cashier_of_permission_change()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_before jsonb;
  v_after jsonb;
BEGIN
  IF current_setting('app.skip_permissions_notification', true) = 'true' THEN
    RETURN NEW;
  END IF;
  v_before := jsonb_build_object(
    'CREATE_SALE', OLD.can_create_sale, 'ACCEPT_CASH', OLD.can_accept_cash,
    'ACCEPT_ONLINE', OLD.can_accept_online, 'CREATE_CREDIT_SALE', OLD.can_create_credit_sale,
    'USE_COUPON', OLD.can_use_coupon, 'ISSUE_INVOICE', OLD.can_issue_invoice,
    'ISSUE_RECEIPT', OLD.can_issue_receipt, 'REFUND', OLD.can_refund, 'VOID', OLD.can_void,
    'CORRECT_AMOUNT', OLD.can_correct_amount, 'CHANGE_PAYMENT_METHOD', OLD.can_change_payment_method,
    'VIEW_BROAD_HISTORY', OLD.can_view_broad_history, 'MANAGE_PRODUCTS', OLD.can_manage_products,
    'VIEW_REPORTS', OLD.can_view_reports
  );
  v_after := jsonb_build_object(
    'CREATE_SALE', NEW.can_create_sale, 'ACCEPT_CASH', NEW.can_accept_cash,
    'ACCEPT_ONLINE', NEW.can_accept_online, 'CREATE_CREDIT_SALE', NEW.can_create_credit_sale,
    'USE_COUPON', NEW.can_use_coupon, 'ISSUE_INVOICE', NEW.can_issue_invoice,
    'ISSUE_RECEIPT', NEW.can_issue_receipt, 'REFUND', NEW.can_refund, 'VOID', NEW.can_void,
    'CORRECT_AMOUNT', NEW.can_correct_amount, 'CHANGE_PAYMENT_METHOD', NEW.can_change_payment_method,
    'VIEW_BROAD_HISTORY', NEW.can_view_broad_history, 'MANAGE_PRODUCTS', NEW.can_manage_products,
    'VIEW_REPORTS', NEW.can_view_reports
  );
  IF v_before IS NOT DISTINCT FROM v_after OR NEW.role <> 'CASHIER'
     OR NOT NEW.is_active OR NEW.removed_at IS NOT NULL THEN
    RETURN NEW;
  END IF;
  PERFORM private.enqueue_business_notification(
    'PERMISSIONS_CHANGED:' || NEW.id || ':' || gen_random_uuid()::text,
    NEW.business_id, NEW.branch_id, NEW.id,
    'PERMISSIONS_CHANGED',
    'Your permissions were updated',
    'An administrator changed your access permissions. Review the updated permissions when you next open the app.',
    jsonb_build_object('before', v_before, 'after', v_after, 'updated_at', NEW.updated_at)
  );
  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION private.notify_cashier_of_permission_change() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS trg_notify_cashier_of_permission_change ON public.business_memberships;
CREATE TRIGGER trg_notify_cashier_of_permission_change
AFTER UPDATE OF can_create_sale, can_accept_cash, can_accept_online, can_create_credit_sale,
  can_use_coupon, can_issue_invoice, can_issue_receipt, can_refund, can_void,
  can_correct_amount, can_change_payment_method, can_view_broad_history,
  can_manage_products, can_view_reports
ON public.business_memberships
FOR EACH ROW
WHEN (OLD.role = 'CASHIER' AND NEW.role = 'CASHIER')
EXECUTE FUNCTION private.notify_cashier_of_permission_change();

-- Admin-only persistent inbox API.
CREATE OR REPLACE FUNCTION public.list_admin_notifications()
RETURNS TABLE(
  id uuid, notification_type text, title text, body text, payload jsonb,
  created_at timestamptz, read_at timestamptz, business_id text,
  branch_id text, branch_name text, recipient_membership_id text
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501'; END IF;
  RETURN QUERY
  SELECT n.id, n.notification_type, n.title, n.body, n.payload, n.created_at, n.read_at,
         n.business_id, n.branch_id, br.name, n.recipient_membership_id
  FROM private.business_notifications n
  JOIN public.business_memberships m ON m.id = n.recipient_membership_id
  JOIN public.profiles p ON p.auth_user_id = m.auth_user_id AND p.is_active = true
  JOIN public.branches br ON br.id = n.branch_id AND br.business_id = n.business_id
  WHERE m.auth_user_id = auth.uid() AND m.role = 'ADMIN' AND m.is_active = true AND m.removed_at IS NULL
  ORDER BY n.created_at DESC
  LIMIT 200;
END;
$function$;

CREATE OR REPLACE FUNCTION public.mark_admin_notification_read(p_notification_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501'; END IF;
  UPDATE private.business_notifications n SET read_at = COALESCE(n.read_at, now())
  WHERE n.id = p_notification_id AND EXISTS (
    SELECT 1 FROM public.business_memberships m
    JOIN public.profiles p ON p.auth_user_id = m.auth_user_id AND p.is_active = true
    WHERE m.id = n.recipient_membership_id AND m.auth_user_id = auth.uid()
      AND m.role = 'ADMIN' AND m.is_active = true AND m.removed_at IS NULL
  );
END;
$function$;

CREATE OR REPLACE FUNCTION public.list_admin_permission_requests()
RETURNS TABLE(
  id uuid, business_id text, branch_id text, branch_name text,
  requester_membership_id text, requester_name text, permission_key text,
  note text, status text, created_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501'; END IF;
  RETURN QUERY
  SELECT r.id, r.business_id, r.branch_id, br.name, r.requester_membership_id,
         COALESCE(si.display_name, cp.display_name, requester.local_user_id, 'Cashier'),
         r.permission_key, r.note, r.status, r.created_at
  FROM private.cashier_permission_requests r
  JOIN public.business_memberships admin ON admin.auth_user_id = auth.uid()
    AND admin.business_id = r.business_id AND admin.branch_id = r.branch_id
    AND admin.role = 'ADMIN' AND admin.is_active = true AND admin.removed_at IS NULL AND admin.staff_identity_id IS NULL
  JOIN public.business_memberships requester ON requester.id = r.requester_membership_id
  LEFT JOIN private.staff_identities si ON si.id = requester.staff_identity_id
  LEFT JOIN public.profiles cp ON cp.auth_user_id = requester.auth_user_id
  JOIN public.branches br ON br.id = r.branch_id AND br.business_id = r.business_id
  WHERE r.status = 'PENDING'
  ORDER BY r.created_at DESC
  LIMIT 100;
END;
$function$;

CREATE OR REPLACE FUNCTION public.resolve_admin_permission_request(
  p_request_id uuid,
  p_decision text,
  p_additional_permissions jsonb DEFAULT '{}'::jsonb
) RETURNS void
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
  IF p_decision NOT IN ('APPROVE','REJECT') OR p_additional_permissions IS NULL
     OR jsonb_typeof(p_additional_permissions) <> 'object' THEN
    RAISE EXCEPTION 'Invalid permission request decision' USING ERRCODE = '22023';
  END IF;
  IF EXISTS (
    SELECT 1 FROM jsonb_each(p_additional_permissions) e
    WHERE e.key NOT IN ('CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE',
      'USE_COUPON','ISSUE_INVOICE','REFUND','VOID','CORRECT_AMOUNT',
      'CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY')
      OR jsonb_typeof(e.value) <> 'boolean' OR e.value <> 'true'::jsonb
  ) THEN RAISE EXCEPTION 'Additional permissions must be known permissions set to true' USING ERRCODE = '22023'; END IF;

  SELECT * INTO v_req FROM private.cashier_permission_requests WHERE id = p_request_id FOR UPDATE;
  IF NOT FOUND OR v_req.status <> 'PENDING' THEN RAISE EXCEPTION 'Permission request not pending' USING ERRCODE = 'P0002'; END IF;
  SELECT * INTO v_admin FROM public.business_memberships m
  WHERE m.auth_user_id = v_actor AND m.business_id = v_req.business_id AND m.branch_id = v_req.branch_id
    AND m.role = 'ADMIN' AND m.is_active = true AND m.removed_at IS NULL AND m.staff_identity_id IS NULL;
  IF NOT FOUND THEN RAISE EXCEPTION 'Admin authority required' USING ERRCODE = '42501'; END IF;
  SELECT * INTO v_target FROM public.business_memberships WHERE id = v_req.requester_membership_id FOR UPDATE;
  IF NOT FOUND OR v_target.role <> 'CASHIER' OR NOT v_target.is_active OR v_target.removed_at IS NOT NULL THEN
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
    UPDATE private.cashier_sync_sessions
       SET revoked_at = COALESCE(revoked_at, now())
     WHERE membership_id = v_target.id AND revoked_at IS NULL;
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

-- Custom PIN-session cashier endpoints call these RPCs through the server API.
CREATE OR REPLACE FUNCTION public.create_cashier_permission_request(
  p_membership_id text, p_permission_key text, p_note text DEFAULT NULL
) RETURNS TABLE(request_id uuid, created_at timestamptz, duplicate boolean)
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
  IF p_membership_id IS NULL OR btrim(p_membership_id) = '' OR
     p_permission_key NOT IN ('CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE',
       'USE_COUPON','ISSUE_INVOICE','REFUND','VOID','CORRECT_AMOUNT',
       'CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY') OR
     (p_note IS NOT NULL AND length(p_note) > 500) THEN
    RAISE EXCEPTION 'Invalid permission request' USING ERRCODE = '22023';
  END IF;
  SELECT * INTO v_member FROM public.business_memberships WHERE id = p_membership_id
    AND role = 'CASHIER' AND is_active = true AND removed_at IS NULL;
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

CREATE OR REPLACE FUNCTION public.list_cashier_notifications(p_membership_id text)
RETURNS TABLE(
  id uuid, notification_type text, title text, body text, payload jsonb,
  created_at timestamptz, read_at timestamptz, business_id text, branch_id text
)
LANGUAGE sql SECURITY DEFINER SET search_path TO ''
AS $function$
  SELECT n.id,n.notification_type,n.title,n.body,n.payload,n.created_at,n.read_at,n.business_id,n.branch_id
  FROM private.business_notifications n
  JOIN public.business_memberships m ON m.id = p_membership_id
  WHERE n.recipient_membership_id = m.id
    AND m.role = 'CASHIER' AND m.is_active = true AND m.removed_at IS NULL
  ORDER BY n.created_at DESC LIMIT 200;
$function$;

CREATE OR REPLACE FUNCTION public.mark_cashier_notification_read(p_membership_id text, p_notification_id uuid)
RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path TO ''
AS $function$
  UPDATE private.business_notifications n SET read_at = COALESCE(n.read_at, now())
  WHERE n.id = p_notification_id AND n.recipient_membership_id = p_membership_id
    AND EXISTS(SELECT 1 FROM public.business_memberships m WHERE m.id = p_membership_id
      AND m.role = 'CASHIER' AND m.is_active = true AND m.removed_at IS NULL);
$function$;

-- Read-only notification access may survive the security session revocation used
-- to force a cashier to refresh permissions. It cannot authorize financial work.
CREATE OR REPLACE FUNCTION public.verify_cashier_notification_session(p_token_hash text)
RETURNS TABLE(membership_id text, business_id text, branch_id text)
LANGUAGE sql SECURITY DEFINER SET search_path TO ''
AS $function$
  SELECT s.membership_id,m.business_id,m.branch_id
  FROM private.cashier_sync_sessions s
  JOIN public.business_memberships m ON m.id = s.membership_id
  WHERE s.token_hash = p_token_hash AND s.expires_at > now()
    AND m.role = 'CASHIER' AND m.is_active = true AND m.removed_at IS NULL
  LIMIT 1;
$function$;

DO $grants$
BEGIN
  REVOKE ALL ON FUNCTION public.list_admin_notifications() FROM PUBLIC, anon;
  GRANT EXECUTE ON FUNCTION public.list_admin_notifications() TO authenticated;
  REVOKE ALL ON FUNCTION public.mark_admin_notification_read(uuid) FROM PUBLIC, anon;
  GRANT EXECUTE ON FUNCTION public.mark_admin_notification_read(uuid) TO authenticated;
  REVOKE ALL ON FUNCTION public.list_admin_permission_requests() FROM PUBLIC, anon;
  GRANT EXECUTE ON FUNCTION public.list_admin_permission_requests() TO authenticated;
  REVOKE ALL ON FUNCTION public.resolve_admin_permission_request(uuid,text,jsonb) FROM PUBLIC, anon;
  GRANT EXECUTE ON FUNCTION public.resolve_admin_permission_request(uuid,text,jsonb) TO authenticated;

  REVOKE ALL ON FUNCTION public.create_cashier_permission_request(text,text,text) FROM PUBLIC, anon, authenticated;
  GRANT EXECUTE ON FUNCTION public.create_cashier_permission_request(text,text,text) TO service_role;
  REVOKE ALL ON FUNCTION public.list_cashier_notifications(text) FROM PUBLIC, anon, authenticated;
  GRANT EXECUTE ON FUNCTION public.list_cashier_notifications(text) TO service_role;
  REVOKE ALL ON FUNCTION public.mark_cashier_notification_read(text,uuid) FROM PUBLIC, anon, authenticated;
  GRANT EXECUTE ON FUNCTION public.mark_cashier_notification_read(text,uuid) TO service_role;
  REVOKE ALL ON FUNCTION public.verify_cashier_notification_session(text) FROM PUBLIC, anon, authenticated;
  GRANT EXECUTE ON FUNCTION public.verify_cashier_notification_session(text) TO service_role;
END;
$grants$;

-- Extend the existing server-side cashier authorization gate for register shift
-- lifecycle commands. Unknown operation types remain denied.
CREATE OR REPLACE FUNCTION public.ingest_cashier_financial_envelope(
  p_business_id text, p_operation_id text, p_branch_id text, p_actor_membership_id text,
  p_device_id text, p_operation_type text, p_schema_version integer,
  p_occurred_at timestamptz, p_branch_timezone_id text, p_business_local_date date,
  p_canonical_text text, p_canonical_sha256 text, p_canonical_payload jsonb
) RETURNS TABLE(status text, operation_id text, canonical_sha256 text, duplicate boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path TO ''
AS $function$
DECLARE
  v_membership public.business_memberships%ROWTYPE;
  v_existing_hash text;
  v_inserted_hash text;
  v_allowed boolean := false;
BEGIN
  SELECT m.* INTO v_membership
  FROM public.business_memberships m
  JOIN private.staff_identities s ON s.id = m.staff_identity_id
  JOIN public.businesses b ON b.id = m.business_id
  JOIN public.branches br ON br.id = m.branch_id AND br.business_id = m.business_id
  WHERE m.id = p_actor_membership_id AND m.business_id = p_business_id AND m.branch_id = p_branch_id
    AND m.role = 'CASHIER' AND m.is_active = true AND m.removed_at IS NULL
    AND s.is_active = true AND b.is_active = true AND br.is_active = true;
  IF NOT FOUND THEN RAISE EXCEPTION 'INVALID_CASHIER_MEMBERSHIP' USING ERRCODE = '42501'; END IF;

  v_allowed := CASE p_operation_type
    WHEN 'RECORD_SALE' THEN v_membership.can_create_sale
    WHEN 'RECORD_CREDIT_REPAYMENT' THEN v_membership.can_create_credit_sale
    WHEN 'RECORD_COUPON_PURCHASE' THEN v_membership.can_use_coupon
    WHEN 'RECORD_REFUND' THEN v_membership.can_refund
    WHEN 'RECORD_VOID' THEN v_membership.can_void
    WHEN 'RECORD_AMOUNT_CORRECTION' THEN v_membership.can_correct_amount
    WHEN 'RECORD_PAYMENT_METHOD_CHANGE' THEN v_membership.can_change_payment_method
    WHEN 'OPEN_CASH_SHIFT' THEN true
    WHEN 'COUNT_CASH_SHIFT' THEN true
    WHEN 'CLOSE_CASH_SHIFT' THEN true
    ELSE false
  END;
  IF NOT COALESCE(v_allowed, false) THEN RAISE EXCEPTION 'OPERATION_NOT_PERMITTED' USING ERRCODE = '42501'; END IF;

  IF p_schema_version IS NULL OR p_schema_version < 1 OR p_device_id IS NULL
     OR length(btrim(p_device_id)) NOT BETWEEN 1 AND 128
     OR p_canonical_text IS NULL OR octet_length(p_canonical_text) NOT BETWEEN 2 AND 262144
     OR p_canonical_sha256 IS NULL OR p_canonical_sha256 !~ '^[0-9a-f]{64}$'
     OR p_canonical_payload IS NULL OR jsonb_typeof(p_canonical_payload) <> 'object' THEN
    RAISE EXCEPTION 'INVALID_ENVELOPE';
  END IF;
  IF p_canonical_sha256 <> encode(extensions.digest(convert_to(p_canonical_text,'UTF8'),'sha256'),'hex') THEN
    RAISE EXCEPTION 'DIGEST_MISMATCH';
  END IF;
  IF p_canonical_payload <> p_canonical_text::jsonb
     OR p_canonical_payload ->> 'operation_id' IS DISTINCT FROM p_operation_id
     OR p_canonical_payload ->> 'business_id' IS DISTINCT FROM p_business_id THEN
    RAISE EXCEPTION 'PAYLOAD_MISMATCH';
  END IF;

  INSERT INTO private.financial_operation_envelopes AS e (
    business_id,operation_id,branch_id,actor_membership_id,device_id,operation_type,
    schema_version,occurred_at,branch_timezone_id,business_local_date,canonical_text,canonical_sha256,canonical_payload
  ) VALUES (
    p_business_id,p_operation_id,p_branch_id,v_membership.id,p_device_id,p_operation_type,
    p_schema_version,p_occurred_at,p_branch_timezone_id,p_business_local_date,p_canonical_text,p_canonical_sha256,p_canonical_payload
  ) ON CONFLICT ON CONSTRAINT financial_operation_envelopes_pkey DO NOTHING
  RETURNING e.canonical_sha256 INTO v_inserted_hash;

  IF v_inserted_hash IS NOT NULL THEN
    RETURN QUERY SELECT 'ACKNOWLEDGED'::text,p_operation_id,p_canonical_sha256,false;
    RETURN;
  END IF;

  SELECT e.canonical_sha256 INTO v_existing_hash FROM private.financial_operation_envelopes e
  WHERE e.business_id = p_business_id AND e.operation_id = p_operation_id;
  IF v_existing_hash IS DISTINCT FROM p_canonical_sha256 THEN
    RAISE EXCEPTION 'OPERATION_COLLISION';
  END IF;
  RETURN QUERY SELECT 'ACKNOWLEDGED'::text,p_operation_id,p_canonical_sha256,true;
END;
$function$;

REVOKE ALL ON FUNCTION public.ingest_cashier_financial_envelope(text,text,text,text,text,text,integer,timestamptz,text,date,text,text,jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.ingest_cashier_financial_envelope(text,text,text,text,text,text,integer,timestamptz,text,date,text,text,jsonb) TO service_role;

-- Ensure the Admin editing RPC accepts the newly introduced customer permission.
CREATE OR REPLACE FUNCTION public.update_admin_cashier_permissions(p_membership_id text, p_permissions jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path TO ''
AS $function$
DECLARE
  v_actor uuid := auth.uid();
  v_target public.business_memberships%ROWTYPE;
  v_before jsonb;
  v_after jsonb;
BEGIN
  IF v_actor IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501'; END IF;
  IF p_membership_id IS NULL OR btrim(p_membership_id) = '' OR p_permissions IS NULL OR jsonb_typeof(p_permissions) <> 'object' THEN
    RAISE EXCEPTION 'Invalid permission update' USING ERRCODE = '22023';
  END IF;
  IF EXISTS (
    SELECT 1 FROM jsonb_object_keys(p_permissions) AS k(key)
    WHERE k.key NOT IN ('CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE','USE_COUPON',
      'ISSUE_INVOICE','ISSUE_RECEIPT','REFUND','VOID','CORRECT_AMOUNT','CHANGE_PAYMENT_METHOD',
      'VIEW_BROAD_HISTORY','MANAGE_PRODUCTS','VIEW_REPORTS')
  ) OR EXISTS (SELECT 1 FROM jsonb_each(p_permissions) e WHERE jsonb_typeof(e.value) <> 'boolean')
    OR (SELECT count(*) FROM jsonb_object_keys(p_permissions)) <> 14 THEN
    RAISE EXCEPTION 'Invalid staff permissions' USING ERRCODE = '22023';
  END IF;

  SELECT c.* INTO v_target FROM public.business_memberships c
  WHERE c.id = p_membership_id AND c.role = 'CASHIER' AND c.staff_identity_id IS NOT NULL AND c.removed_at IS NULL FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Cashier not found' USING ERRCODE = 'P0002'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.business_memberships a
    JOIN public.profiles p ON p.auth_user_id = a.auth_user_id AND p.is_active = true
    JOIN public.businesses b ON b.id = a.business_id AND b.is_active = true
    JOIN public.branches br ON br.id = a.branch_id AND br.business_id = a.business_id AND br.is_active = true
    WHERE a.auth_user_id = v_actor AND a.business_id = v_target.business_id AND a.branch_id = v_target.branch_id
      AND a.role = 'ADMIN' AND a.is_active = true AND a.removed_at IS NULL AND a.staff_identity_id IS NULL
  ) THEN RAISE EXCEPTION 'Admin authority required for this cashier' USING ERRCODE = '42501'; END IF;

  v_before := jsonb_build_object(
    'CREATE_SALE',v_target.can_create_sale,'ACCEPT_CASH',v_target.can_accept_cash,
    'ACCEPT_ONLINE',v_target.can_accept_online,'CREATE_CREDIT_SALE',v_target.can_create_credit_sale,
    'USE_COUPON',v_target.can_use_coupon,'ISSUE_INVOICE',v_target.can_issue_invoice,
    'ISSUE_RECEIPT',v_target.can_issue_receipt,'REFUND',v_target.can_refund,'VOID',v_target.can_void,
    'CORRECT_AMOUNT',v_target.can_correct_amount,'CHANGE_PAYMENT_METHOD',v_target.can_change_payment_method,
    'VIEW_BROAD_HISTORY',v_target.can_view_broad_history,'MANAGE_PRODUCTS',v_target.can_manage_products,
    'VIEW_REPORTS',v_target.can_view_reports
  );
  v_after := p_permissions;
  UPDATE public.business_memberships SET
    can_create_sale=(p_permissions->>'CREATE_SALE')::boolean,
    can_accept_cash=(p_permissions->>'ACCEPT_CASH')::boolean,
    can_accept_online=(p_permissions->>'ACCEPT_ONLINE')::boolean,
    can_create_credit_sale=(p_permissions->>'CREATE_CREDIT_SALE')::boolean,
    can_use_coupon=(p_permissions->>'USE_COUPON')::boolean,
    can_issue_invoice=(p_permissions->>'ISSUE_INVOICE')::boolean,
    can_issue_receipt=(p_permissions->>'ISSUE_RECEIPT')::boolean,
    can_refund=(p_permissions->>'REFUND')::boolean,
    can_void=(p_permissions->>'VOID')::boolean,
    can_correct_amount=(p_permissions->>'CORRECT_AMOUNT')::boolean,
    can_change_payment_method=(p_permissions->>'CHANGE_PAYMENT_METHOD')::boolean,
    can_view_broad_history=(p_permissions->>'VIEW_BROAD_HISTORY')::boolean,
    can_manage_products=(p_permissions->>'MANAGE_PRODUCTS')::boolean,
    can_view_reports=(p_permissions->>'VIEW_REPORTS')::boolean,
    updated_at=now()
  WHERE id=v_target.id;
  UPDATE private.cashier_sync_sessions SET revoked_at=COALESCE(revoked_at,now())
  WHERE membership_id=v_target.id AND revoked_at IS NULL;
  INSERT INTO private.staff_admin_audit_events(actor_auth_user_id,business_id,branch_id,membership_id,event_type,before_state,after_state,occurred_at)
  VALUES(v_actor,v_target.business_id,v_target.branch_id,v_target.id,'PERMISSIONS_UPDATED',v_before,v_after,now());
END;
$function$;
REVOKE ALL ON FUNCTION public.update_admin_cashier_permissions(text,jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.update_admin_cashier_permissions(text,jsonb) TO authenticated;





-- Admin realtime is delivered only to rows the authenticated Admin may read.
-- Cashiers use the custom PIN session and continue using the authenticated API.
GRANT USAGE ON SCHEMA private TO authenticated;
GRANT SELECT ON TABLE private.business_notifications TO authenticated;
DROP POLICY IF EXISTS admin_read_own_business_notifications ON private.business_notifications;
CREATE POLICY admin_read_own_business_notifications
ON private.business_notifications FOR SELECT TO authenticated
USING (
  EXISTS (
    SELECT 1 FROM public.business_memberships m
    JOIN public.profiles p ON p.auth_user_id = m.auth_user_id AND p.is_active = true
    WHERE m.id = business_notifications.recipient_membership_id
      AND m.auth_user_id = auth.uid()
      AND m.role = 'ADMIN' AND m.is_active = true AND m.removed_at IS NULL
  )
);
DO $publication$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_catalog.pg_publication WHERE pubname = 'supabase_realtime')
     AND NOT EXISTS (
       SELECT 1 FROM pg_catalog.pg_publication_tables
       WHERE pubname = 'supabase_realtime'
         AND schemaname = 'private' AND tablename = 'business_notifications'
     ) THEN
    EXECUTE 'ALTER PUBLICATION supabase_realtime ADD TABLE private.business_notifications';
  END IF;
END;
$publication$;

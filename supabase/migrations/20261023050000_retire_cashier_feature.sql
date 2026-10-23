-- Retire the cashier/staff feature without deleting financial ledger or actor references.
-- Cashier memberships remain as inactive, anonymized tombstones because financial envelopes,
-- customer-directory audit references, and historical accounting evidence point to their IDs.

ALTER TABLE public.business_memberships
  DROP CONSTRAINT business_memberships_principal_role_check;

ALTER TABLE public.business_memberships
  ADD CONSTRAINT business_memberships_principal_role_check CHECK (
    (role = 'ADMIN' AND auth_user_id IS NOT NULL AND staff_identity_id IS NULL)
    OR (role = 'CASHIER' AND auth_user_id IS NULL AND (
      staff_identity_id IS NOT NULL
      OR (staff_identity_id IS NULL AND is_active = false AND removed_at IS NOT NULL)
    ))
  );

UPDATE public.business_memberships
SET is_active = false,
    removed_at = COALESCE(removed_at, now()),
    auth_user_id = NULL,
    local_user_id = NULL,
    staff_identity_id = NULL,
    can_create_sale = false,
    can_accept_cash = false,
    can_accept_online = false,
    can_create_credit_sale = false,
    can_use_coupon = false,
    can_issue_invoice = false,
    can_issue_receipt = false,
    can_refund = false,
    can_void = false,
    can_correct_amount = false,
    can_change_payment_method = false,
    can_view_broad_history = false,
    can_manage_products = false,
    can_view_reports = false,
    can_create_expense = false,
    can_view_customer_directory = false,
    updated_at = now()
WHERE role = 'CASHIER';

DELETE FROM private.cashier_sync_sessions;
DELETE FROM private.staff_invite_activations;
DELETE FROM private.staff_invites;
DELETE FROM private.cashier_permission_requests;
DELETE FROM private.staff_admin_audit_events;

DELETE FROM private.business_notifications
WHERE notification_type IN (
  'PERMISSIONS_CHANGED',
  'CASHIER_PERMISSIONS_CHANGED',
  'PERMISSION_REQUEST',
  'PERMISSION_REQUEST_RESULT'
);

DELETE FROM private.staff_identities;

-- Keep local historical actor IDs usable for replay, but remove staff names and permissions.
UPDATE public.business_memberships SET display_name = 'Retired staff' WHERE role = 'CASHIER';

-- Remove callable cashier/staff RPCs. Admin financial upload/download and customer-directory
-- RPCs are intentionally untouched.
DROP TRIGGER IF EXISTS trg_notify_cashier_of_permission_change ON public.business_memberships;
DROP FUNCTION IF EXISTS private.notify_cashier_of_permission_change();
DROP FUNCTION IF EXISTS public.activate_staff_invite(text, text, text);
DROP FUNCTION IF EXISTS public.cashier_login_credential(text);
DROP FUNCTION IF EXISTS public.cashier_restoration_payload(text);
DROP FUNCTION IF EXISTS public.create_cashier_permission_request(text, text, text);
DROP FUNCTION IF EXISTS public.create_cashier_sync_session(text, text);
DROP FUNCTION IF EXISTS public.create_staff_invite_for_admin(text, text, text, text, text, jsonb, uuid);
DROP FUNCTION IF EXISTS public.fetch_cashier_financial_envelopes(text, text, text, timestamptz, text, integer);
DROP FUNCTION IF EXISTS public.fetch_cashier_financial_envelopes_v2(text, text, text, timestamptz, text, integer);
DROP FUNCTION IF EXISTS public.fetch_cashier_metadata(text, text, text);
DROP FUNCTION IF EXISTS public.fetch_cashier_restoration(text);
DROP FUNCTION IF EXISTS public.list_admin_cashiers();
DROP FUNCTION IF EXISTS public.list_admin_cashiers_with_permissions();
DROP FUNCTION IF EXISTS public.list_admin_permission_requests();
DROP FUNCTION IF EXISTS public.list_cashier_notifications(text);
DROP FUNCTION IF EXISTS public.list_staff_invites(text);
DROP FUNCTION IF EXISTS public.mark_cashier_notification_read(text, uuid);
DROP FUNCTION IF EXISTS public.preview_staff_invite(text);
DROP FUNCTION IF EXISTS public.remove_admin_cashier(text);
DROP FUNCTION IF EXISTS public.resolve_admin_permission_request(uuid, text, jsonb);
DROP FUNCTION IF EXISTS public.revoke_staff_invite(uuid);
DROP FUNCTION IF EXISTS public.update_admin_cashier_permissions(text, jsonb);
DROP FUNCTION IF EXISTS public.update_cashier_permissions(text, jsonb);
DROP FUNCTION IF EXISTS public.verify_cashier_notification_session(text);
DROP FUNCTION IF EXISTS public.verify_cashier_sync_session(text);
DROP FUNCTION IF EXISTS public.ingest_cashier_financial_envelope(text, text, text, text, text, text, integer, timestamptz, text, date, text, text, jsonb);


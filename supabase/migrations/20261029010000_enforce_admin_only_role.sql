-- Final CASHIER retirement guard.
-- Historical inactive membership tombstones and financial actor IDs remain readable.
-- New CASHIER memberships and any reactivation/permission grant are prohibited.

ALTER TABLE public.business_memberships
  DROP CONSTRAINT IF EXISTS business_memberships_cashier_retirement_check;

ALTER TABLE public.business_memberships
  ADD CONSTRAINT business_memberships_cashier_retirement_check CHECK (
    role <> 'CASHIER'
    OR (
      is_active = false
      AND removed_at IS NOT NULL
      AND auth_user_id IS NULL
      AND staff_identity_id IS NULL
      AND can_create_sale = false
      AND can_accept_cash = false
      AND can_accept_online = false
      AND can_create_credit_sale = false
      AND can_use_coupon = false
      AND can_issue_invoice = false
      AND can_issue_receipt = false
      AND can_refund = false
      AND can_void = false
      AND can_correct_amount = false
      AND can_change_payment_method = false
      AND can_view_broad_history = false
      AND can_manage_products = false
      AND can_view_reports = false
      AND can_create_expense = false
      AND can_view_customer_directory = false
    )
  );

CREATE OR REPLACE FUNCTION private.prevent_new_cashier_memberships()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $function$
BEGIN
  IF TG_OP = 'INSERT' AND NEW.role = 'CASHIER' THEN
    RAISE EXCEPTION 'CASHIER role is retired; create ADMIN memberships only'
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.role = 'CASHIER' THEN
    IF OLD.role <> 'CASHIER' THEN
      RAISE EXCEPTION 'CASHIER role is retired; role conversion to CASHIER is prohibited'
        USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.is_active IS DISTINCT FROM false
       OR NEW.removed_at IS NULL
       OR NEW.auth_user_id IS NOT NULL
       OR NEW.staff_identity_id IS NOT NULL
       OR NEW.can_create_sale
       OR NEW.can_accept_cash
       OR NEW.can_accept_online
       OR NEW.can_create_credit_sale
       OR NEW.can_use_coupon
       OR NEW.can_issue_invoice
       OR NEW.can_issue_receipt
       OR NEW.can_refund
       OR NEW.can_void
       OR NEW.can_correct_amount
       OR NEW.can_change_payment_method
       OR NEW.can_view_broad_history
       OR NEW.can_manage_products
       OR NEW.can_view_reports
       OR NEW.can_create_expense
       OR NEW.can_view_customer_directory THEN
      RAISE EXCEPTION 'Historical CASHIER tombstones cannot be activated or granted permissions'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_prevent_new_cashier_memberships ON public.business_memberships;
CREATE TRIGGER trg_prevent_new_cashier_memberships
BEFORE INSERT OR UPDATE OF role, is_active, removed_at, auth_user_id, staff_identity_id,
  can_create_sale, can_accept_cash, can_accept_online, can_create_credit_sale, can_use_coupon,
  can_issue_invoice, can_issue_receipt, can_refund, can_void, can_correct_amount,
  can_change_payment_method, can_view_broad_history, can_manage_products, can_view_reports,
  can_create_expense, can_view_customer_directory
ON public.business_memberships
FOR EACH ROW
EXECUTE FUNCTION private.prevent_new_cashier_memberships();

COMMENT ON FUNCTION private.prevent_new_cashier_memberships() IS
  'ADMIN-only product guard. Allows existing inactive CASHIER tombstones solely for historical actor/financial compatibility.';

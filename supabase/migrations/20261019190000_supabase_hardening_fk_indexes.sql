-- Backward-compatible query-planning improvement only.
-- No data, RPC signatures, authorization rules, or transaction semantics change.
-- These indexes support FK maintenance and join paths used by privileged sync/admin RPCs.
CREATE INDEX IF NOT EXISTS idx_business_notifications_branch_id
  ON private.business_notifications (branch_id);
CREATE INDEX IF NOT EXISTS idx_business_notifications_business_id
  ON private.business_notifications (business_id);

CREATE INDEX IF NOT EXISTS idx_cashier_permission_requests_branch_id
  ON private.cashier_permission_requests (branch_id);
CREATE INDEX IF NOT EXISTS idx_cashier_permission_requests_business_id
  ON private.cashier_permission_requests (business_id);
CREATE INDEX IF NOT EXISTS idx_cashier_permission_requests_requester_membership_id
  ON private.cashier_permission_requests (requester_membership_id);
CREATE INDEX IF NOT EXISTS idx_cashier_permission_requests_resolved_by_auth_user_id
  ON private.cashier_permission_requests (resolved_by_auth_user_id);

CREATE INDEX IF NOT EXISTS idx_customer_directory_created_by_membership_id
  ON private.customer_directory (created_by_membership_id);
CREATE INDEX IF NOT EXISTS idx_customer_directory_updated_by_membership_id
  ON private.customer_directory (updated_by_membership_id);

CREATE INDEX IF NOT EXISTS idx_financial_envelopes_actor_membership_id
  ON private.financial_operation_envelopes (actor_membership_id);

CREATE INDEX IF NOT EXISTS idx_staff_invite_activations_staff_identity_id
  ON private.staff_invite_activations (staff_identity_id);

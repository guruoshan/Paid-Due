-- A staff identity can be reactivated by a later invitation while retaining the same stable local_user_id.
-- Activation evidence is per invitation and membership; local_user_id is intentionally reusable across reactivations.
ALTER TABLE private.staff_invite_activations
  DROP CONSTRAINT IF EXISTS staff_invite_activations_local_user_unique;

CREATE INDEX IF NOT EXISTS idx_staff_invite_activations_local_user
  ON private.staff_invite_activations (local_user_id);

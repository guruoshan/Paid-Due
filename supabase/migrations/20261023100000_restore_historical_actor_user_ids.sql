-- Restore the immutable actor-to-membership mapping needed by admin financial downloads.
-- Only IDs already present in signed/canonical financial envelopes are restored; no login is re-enabled.
UPDATE public.business_memberships AS m
SET local_user_id = actors.actor_user_id,
    updated_at = now()
FROM (
  SELECT actor_membership_id,
         min(canonical_payload->>'actor_user_id') AS actor_user_id
  FROM private.financial_operation_envelopes
  WHERE canonical_payload ? 'actor_user_id'
    AND nullif(canonical_payload->>'actor_user_id', '') IS NOT NULL
  GROUP BY actor_membership_id
  HAVING count(DISTINCT canonical_payload->>'actor_user_id') = 1
) AS actors
WHERE m.id = actors.actor_membership_id
  AND m.role = 'CASHIER'
  AND m.is_active = false
  AND m.removed_at IS NOT NULL
  AND m.local_user_id IS NULL;

-- Semantics-preserving RLS planning improvement.
-- Keep the exact authorization predicate; evaluate auth.uid() once per statement
-- rather than once per candidate row.
DROP POLICY IF EXISTS admin_read_own_business_notifications
  ON private.business_notifications;

CREATE POLICY admin_read_own_business_notifications
  ON private.business_notifications
  FOR SELECT
  TO authenticated
  USING (
    EXISTS (
      SELECT 1
      FROM public.business_memberships m
      JOIN public.profiles p
        ON p.auth_user_id = m.auth_user_id
       AND p.is_active = true
      WHERE m.id = business_notifications.recipient_membership_id
        AND m.auth_user_id = (SELECT auth.uid())
        AND m.role = 'ADMIN'
        AND m.is_active = true
        AND m.removed_at IS NULL
    )
  );

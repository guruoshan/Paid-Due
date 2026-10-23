-- Remove any remaining inbox entries addressed to retired cashier memberships.
DELETE FROM private.business_notifications
WHERE recipient_membership_id IN (
  SELECT id FROM public.business_memberships WHERE role = 'CASHIER'
)
OR notification_type IN (
  'PERMISSIONS_CHANGED',
  'CASHIER_PERMISSIONS_CHANGED',
  'PERMISSION_REQUEST',
  'PERMISSION_REQUEST_RESULT'
);

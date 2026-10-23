-- Anonymize inactive cashier membership labels while preserving actor IDs used by ledger history.
UPDATE public.business_memberships
SET display_name = 'Retired staff'
WHERE role = 'CASHIER'
  AND is_active = false
  AND removed_at IS NOT NULL;

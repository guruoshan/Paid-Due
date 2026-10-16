-- Permit a removed cashier identity to rejoin the same branch without deleting historical membership rows.
-- Only live memberships must be unique per staff identity and branch.
DROP INDEX IF EXISTS public.uq_business_memberships_staff_branch;

CREATE UNIQUE INDEX uq_business_memberships_staff_branch
ON public.business_memberships (staff_identity_id, branch_id)
WHERE staff_identity_id IS NOT NULL
  AND is_active = true
  AND removed_at IS NULL;

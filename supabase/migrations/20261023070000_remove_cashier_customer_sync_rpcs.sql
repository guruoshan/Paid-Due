-- Retire the legacy branch-scoped customer directory RPCs that accepted cashier memberships.
-- Admin sync continues through sync_customer_directory_admin_list/upsert.
DROP FUNCTION IF EXISTS public.sync_customer_directory_list(text, text, text, timestamptz, integer);
DROP FUNCTION IF EXISTS public.sync_customer_directory_upsert(text, text, text, text, text, text, timestamptz);

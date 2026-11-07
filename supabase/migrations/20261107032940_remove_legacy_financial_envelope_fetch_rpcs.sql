-- Retire the two superseded Admin financial-envelope download RPCs.
-- The current Android sync path uses fetch_financial_envelopes_v3.
-- Reporting continues to use fetch_financial_reporting_inventory_v1.
-- Deliberately omit CASCADE: unexpected dependencies must stop this migration.
DROP FUNCTION IF EXISTS public.fetch_financial_envelopes(text, timestamptz, integer);
DROP FUNCTION IF EXISTS public.fetch_financial_envelopes_v2(text, timestamptz, text, integer);

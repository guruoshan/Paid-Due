-- TASK 3B — Proposal only. DO NOT APPLY until a complete call-site/external-client audit confirms
-- the legacy v1/v2 RPCs are no longer used. The current Android Admin sync path reviewed uses v3.
-- Goal: stop direct authenticated-client execution of legacy SECURITY DEFINER envelope fetches.
--
-- Rollback:
--   GRANT EXECUTE ON FUNCTION public.fetch_financial_envelopes(text,timestamptz,integer) TO authenticated;
--   GRANT EXECUTE ON FUNCTION public.fetch_financial_envelopes_v2(text,timestamptz,text,integer) TO authenticated;
--   REVOKE EXECUTE ON FUNCTION public.fetch_financial_envelopes(text,timestamptz,integer) FROM service_role;
--   REVOKE EXECUTE ON FUNCTION public.fetch_financial_envelopes_v2(text,timestamptz,text,integer) FROM service_role;
-- This restores the currently observed authenticated grants; do not grant to PUBLIC or anon.

BEGIN;

REVOKE ALL ON FUNCTION public.fetch_financial_envelopes(text, timestamptz, integer)
  FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.fetch_financial_envelopes_v2(text, timestamptz, text, integer)
  FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION public.fetch_financial_envelopes(text, timestamptz, integer)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.fetch_financial_envelopes_v2(text, timestamptz, text, integer)
  TO service_role;

COMMIT;

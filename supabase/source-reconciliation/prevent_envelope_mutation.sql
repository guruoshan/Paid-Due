-- SOURCE RECONCILIATION ONLY. NOT A MIGRATION. DO NOT APPLY.
-- Recorded from production catalog on 2026-11-07, normalised body SHA-256 209c0bcec37edf8a6a14fea298748ad16dc705fe1f3834c2726b55fd8bea4afa.
-- Applied out of band before this repository tracked it. Migration ledger status: see reconciliation doc.

CREATE OR REPLACE FUNCTION private.prevent_envelope_mutation()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
BEGIN
    RAISE EXCEPTION 'Financial envelopes are immutable';
END;
$function$

-- -----------------------------------------------------------------------------
-- RECORDED TRIGGER DEFINITIONS ONLY. DO NOT APPLY.
-- CREATE TRIGGER financial_envelope_immutable BEFORE DELETE OR UPDATE ON private.financial_operation_envelopes FOR EACH ROW EXECUTE FUNCTION private.prevent_envelope_mutation()

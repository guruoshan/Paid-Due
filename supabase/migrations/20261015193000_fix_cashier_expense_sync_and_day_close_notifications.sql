-- Restore cashier expense envelope ingestion without weakening the existing
-- membership, branch, timezone, payload digest, collision, or idempotency checks.
-- Patch the already-authoritative function body rather than duplicating its
-- long security-sensitive implementation in this migration.
DO $migration$
DECLARE
  v_definition text;
  v_old text := $old$    WHEN 'RECORD_PAYMENT_METHOD_CHANGE' THEN v_membership.can_change_payment_method$old$;
  v_new text := $new$    WHEN 'RECORD_PAYMENT_METHOD_CHANGE' THEN v_membership.can_change_payment_method
    WHEN 'RECORD_EXPENSE' THEN
      CASE p_canonical_payload #>> '{expense,payment_category}'
        WHEN 'CASH' THEN v_membership.can_accept_cash
        WHEN 'ONLINE' THEN v_membership.can_accept_online
        ELSE false
      END$new$;
BEGIN
  v_definition := pg_catalog.pg_get_functiondef(
    'public.ingest_cashier_financial_envelope(text,text,text,text,text,text,integer,timestamptz,text,date,text,text,jsonb)'::regprocedure
  );
  IF pg_catalog.position(v_old IN v_definition) = 0 THEN
    RAISE EXCEPTION 'Expected expense-ingest authorization anchor not found; refusing unsafe patch';
  END IF;
  v_definition := pg_catalog.replace(v_definition, v_old, v_new);
  EXECUTE v_definition;
END
$migration$;

-- Make the push/inbox preview useful, while keeping the structured report as
-- the authoritative detailed breakdown in the notification payload.
DO $migration$
DECLARE
  v_definition text;
  v_old text := $old$  v_body := CASE
    WHEN v_report IS NULL OR jsonb_typeof(v_report) <> 'object'
      THEN COALESCE(v_actor_name, 'Cashier') || ' closed a shift. Open the closing record for available details.'
    ELSE COALESCE(v_actor_name, 'Cashier') || ' closed a shift. Review sales, payment methods, and cash reconciliation.'
  END;$old$;
  v_new text := $new$  v_body := CASE
    WHEN v_report IS NULL OR jsonb_typeof(v_report) <> 'object'
      THEN COALESCE(v_actor_name, 'Cashier') || ' closed a shift. Open the closing record for available details.'
    ELSE
      COALESCE(v_actor_name, 'Cashier') || ' closed shift. Sales ' ||
      COALESCE(v_report ->> 'currency_symbol', v_report ->> 'currency_code', 'OMR') || ' ' ||
      round(COALESCE(NULLIF(v_report ->> 'gross_sales_minor', '')::numeric, 0) /
        power(10, LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3)),
        LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3))::text ||
      ' (cash ' || round(COALESCE(NULLIF(v_report ->> 'cash_sales_minor', '')::numeric, 0) /
        power(10, LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3)),
        LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3))::text ||
      ', online ' || round(COALESCE(NULLIF(v_report ->> 'online_sales_minor', '')::numeric, 0) /
        power(10, LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3)),
        LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3))::text ||
      ', credit ' || round(COALESCE(NULLIF(v_report ->> 'credit_given_minor', '')::numeric, 0) /
        power(10, LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3)),
        LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3))::text ||
      '); coupon top-ups ' || round(COALESCE(NULLIF(v_report ->> 'coupon_top_up_minor', '')::numeric, 0) /
        power(10, LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3)),
        LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3))::text ||
      '; counted cash ' || round(COALESCE(NULLIF(v_report ->> 'counted_cash_minor', '')::numeric, 0) /
        power(10, LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3)),
        LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3))::text ||
      '; cash difference ' || round(COALESCE(NULLIF(v_report ->> 'difference_minor', '')::numeric, 0) /
        power(10, LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3)),
        LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3))::text || '.'
  END;$new$;
BEGIN
  v_definition := pg_catalog.pg_get_functiondef('private.notify_admins_of_confirmed_day_close()'::regprocedure);
  IF pg_catalog.position(v_old IN v_definition) = 0 THEN
    RAISE EXCEPTION 'Expected day-close notification body anchor not found; refusing unsafe patch';
  END IF;
  v_definition := pg_catalog.replace(v_definition, v_old, v_new);
  EXECUTE v_definition;
END
$migration$;

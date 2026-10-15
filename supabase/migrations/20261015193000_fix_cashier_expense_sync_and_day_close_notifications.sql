-- Restore cashier expense envelope ingestion while retaining tenant, membership,
-- branch/timezone, payload digest, canonical payload, and idempotency checks.
CREATE OR REPLACE FUNCTION public.ingest_cashier_financial_envelope(
  p_business_id text,
  p_operation_id text,
  p_branch_id text,
  p_actor_membership_id text,
  p_device_id text,
  p_operation_type text,
  p_schema_version integer,
  p_occurred_at timestamp with time zone,
  p_branch_timezone_id text,
  p_business_local_date date,
  p_canonical_text text,
  p_canonical_sha256 text,
  p_canonical_payload jsonb
)
RETURNS TABLE(status text, operation_id text, canonical_sha256 text, duplicate boolean)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_membership public.business_memberships%ROWTYPE;
  v_existing_hash text;
  v_inserted_hash text;
  v_allowed boolean := false;
BEGIN
  IF p_business_id IS NULL OR btrim(p_business_id) = ''
     OR p_operation_id IS NULL OR btrim(p_operation_id) = ''
     OR p_branch_id IS NULL OR btrim(p_branch_id) = ''
     OR p_actor_membership_id IS NULL OR btrim(p_actor_membership_id) = ''
     OR p_device_id IS NULL OR length(btrim(p_device_id)) NOT BETWEEN 1 AND 128
     OR p_operation_type IS NULL OR btrim(p_operation_type) = ''
     OR p_schema_version IS NULL OR p_schema_version < 1
     OR p_occurred_at IS NULL
     OR p_branch_timezone_id IS NULL OR btrim(p_branch_timezone_id) = ''
     OR p_business_local_date IS NULL
     OR p_canonical_text IS NULL OR octet_length(p_canonical_text) NOT BETWEEN 2 AND 262144
     OR p_canonical_sha256 IS NULL OR p_canonical_sha256 !~ '^[0-9a-f]{64}$'
     OR p_canonical_payload IS NULL OR jsonb_typeof(p_canonical_payload) <> 'object'
  THEN
    RAISE EXCEPTION 'INVALID_ENVELOPE' USING ERRCODE = '22023';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_timezone_names tz
    WHERE tz.name = p_branch_timezone_id
  ) THEN
    RAISE EXCEPTION 'INVALID_BRANCH_TIMEZONE' USING ERRCODE = '22023';
  END IF;
  IF p_business_local_date <> (p_occurred_at AT TIME ZONE p_branch_timezone_id)::date THEN
    RAISE EXCEPTION 'BUSINESS_LOCAL_DATE_MISMATCH' USING ERRCODE = '22023';
  END IF;

  SELECT m.* INTO v_membership
  FROM public.business_memberships m
  JOIN private.staff_identities s
    ON s.id = m.staff_identity_id AND s.is_active = true
  JOIN public.businesses b
    ON b.id = m.business_id AND b.is_active = true
  JOIN public.branches br
    ON br.id = m.branch_id
   AND br.business_id = m.business_id
   AND br.is_active = true
   AND br.timezone_id = p_branch_timezone_id
  WHERE m.id = p_actor_membership_id
    AND m.business_id = p_business_id
    AND m.branch_id = p_branch_id
    AND m.role = 'CASHIER'
    AND m.is_active = true
    AND m.removed_at IS NULL;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'INVALID_CASHIER_MEMBERSHIP' USING ERRCODE = '42501';
  END IF;

  v_allowed := CASE p_operation_type
    WHEN 'RECORD_SALE' THEN
      v_membership.can_create_sale
      AND CASE
        WHEN jsonb_typeof(p_canonical_payload -> 'settlements') = 'array' THEN
          jsonb_array_length(p_canonical_payload -> 'settlements') > 0
          AND NOT EXISTS (
            SELECT 1
            FROM jsonb_array_elements(p_canonical_payload -> 'settlements') AS settlement(value)
            WHERE jsonb_typeof(settlement.value) IS DISTINCT FROM 'object'
              OR jsonb_typeof(settlement.value -> 'category') IS DISTINCT FROM 'string'
              OR settlement.value ->> 'category' NOT IN ('CASH', 'ONLINE', 'CREDIT', 'COUPON')
              OR (settlement.value ->> 'category' = 'CASH' AND NOT v_membership.can_accept_cash)
              OR (settlement.value ->> 'category' = 'ONLINE' AND NOT v_membership.can_accept_online)
              OR (settlement.value ->> 'category' = 'CREDIT' AND NOT v_membership.can_create_credit_sale)
              OR (settlement.value ->> 'category' = 'COUPON' AND NOT v_membership.can_use_coupon)
          )
        ELSE false
      END
    WHEN 'RECORD_CREDIT_REPAYMENT' THEN v_membership.can_create_credit_sale
    WHEN 'RECORD_COUPON_PURCHASE' THEN v_membership.can_use_coupon
    WHEN 'RECORD_REFUND' THEN v_membership.can_refund
    WHEN 'RECORD_VOID' THEN v_membership.can_void
    WHEN 'RECORD_AMOUNT_CORRECTION' THEN v_membership.can_correct_amount
    WHEN 'RECORD_PAYMENT_METHOD_CHANGE' THEN v_membership.can_change_payment_method
    WHEN 'RECORD_EXPENSE' THEN
      CASE p_canonical_payload #>> '{expense,payment_category}'
        WHEN 'CASH' THEN v_membership.can_accept_cash
        WHEN 'ONLINE' THEN v_membership.can_accept_online
        ELSE false
      END
    WHEN 'OPEN_CASH_SHIFT' THEN true
    WHEN 'COUNT_CASH_SHIFT' THEN true
    WHEN 'CLOSE_CASH_SHIFT' THEN true
    ELSE false
  END;

  IF NOT COALESCE(v_allowed, false) THEN
    RAISE EXCEPTION 'OPERATION_NOT_PERMITTED' USING ERRCODE = '42501';
  END IF;

  IF p_canonical_payload ->> 'operation_id' IS DISTINCT FROM p_operation_id
     OR p_canonical_payload ->> 'business_id' IS DISTINCT FROM p_business_id
     OR p_canonical_payload ->> 'branch_id' IS DISTINCT FROM p_branch_id
     OR p_canonical_payload ->> 'operation_type' IS DISTINCT FROM p_operation_type
     OR p_canonical_payload ->> 'actor_user_id' IS DISTINCT FROM v_membership.local_user_id
     OR (CASE
          WHEN COALESCE(p_canonical_payload ->> 'command_schema_version', '') ~ '^[1-9][0-9]{0,8}$'
            THEN (p_canonical_payload ->> 'command_schema_version')::integer = p_schema_version
          ELSE false
        END) IS NOT TRUE
     OR (CASE
          WHEN COALESCE(p_canonical_payload ->> 'occurred_at', '') ~ '^[0-9]{1,16}$'
            THEN (p_canonical_payload ->> 'occurred_at')::bigint =
              floor(extract(epoch FROM p_occurred_at) * 1000)::bigint
          ELSE false
        END) IS NOT TRUE
     OR COALESCE(p_canonical_payload ->> 'request_fingerprint', '') = ''
  THEN
    RAISE EXCEPTION 'PAYLOAD_METADATA_MISMATCH' USING ERRCODE = '22023';
  END IF;

  IF p_canonical_sha256 <> encode(
    extensions.digest(convert_to(p_canonical_text, 'UTF8'), 'sha256'),
    'hex'
  ) THEN
    RAISE EXCEPTION 'DIGEST_MISMATCH' USING ERRCODE = '22023';
  END IF;

  IF p_canonical_payload <> p_canonical_text::jsonb THEN
    RAISE EXCEPTION 'PAYLOAD_MISMATCH' USING ERRCODE = '22023';
  END IF;

  INSERT INTO private.financial_operation_envelopes AS e (
    business_id, operation_id, branch_id, actor_membership_id, device_id,
    operation_type, schema_version, occurred_at, branch_timezone_id,
    business_local_date, canonical_text, canonical_sha256, canonical_payload
  )
  VALUES (
    p_business_id, p_operation_id, p_branch_id, v_membership.id, p_device_id,
    p_operation_type, p_schema_version, p_occurred_at, p_branch_timezone_id,
    p_business_local_date, p_canonical_text, p_canonical_sha256, p_canonical_payload
  )
  ON CONFLICT ON CONSTRAINT financial_operation_envelopes_pkey DO NOTHING
  RETURNING e.canonical_sha256 INTO v_inserted_hash;

  IF v_inserted_hash IS NOT NULL THEN
    RETURN QUERY SELECT 'ACKNOWLEDGED'::text, p_operation_id, p_canonical_sha256, false;
    RETURN;
  END IF;

  SELECT e.canonical_sha256 INTO v_existing_hash
  FROM private.financial_operation_envelopes e
  WHERE e.business_id = p_business_id AND e.operation_id = p_operation_id;
  IF v_existing_hash IS DISTINCT FROM p_canonical_sha256 THEN
    RAISE EXCEPTION 'OPERATION_COLLISION';
  END IF;

  RETURN QUERY SELECT 'ACKNOWLEDGED'::text, p_operation_id, p_canonical_sha256, true;
END;
$function$;

-- Include key totals in the notification preview and retain the complete
-- structured report in the notification payload for the detailed inbox card.
CREATE OR REPLACE FUNCTION private.notify_admins_of_confirmed_day_close()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_actor_name text;
  v_report jsonb;
  v_difference_minor text;
  v_body text;
  v_admin record;
  v_decimals integer;
  v_currency text;
BEGIN
  IF NEW.operation_type <> 'CLOSE_CASH_SHIFT' THEN
    RETURN NEW;
  END IF;

  v_report := NEW.canonical_payload -> 'day_closing_report';
  v_difference_minor := COALESCE(v_report ->> 'difference_minor', '0');
  v_decimals := LEAST(GREATEST(COALESCE(NULLIF(v_report ->> 'currency_decimal_places', '')::integer, 3), 0), 3);
  v_currency := COALESCE(NULLIF(v_report ->> 'currency_symbol', ''), NULLIF(v_report ->> 'currency_code', ''), 'OMR');
  SELECT COALESCE(si.display_name, p.display_name, NEW.canonical_payload #>> '{operation,actor_user_id}', 'Cashier')
    INTO v_actor_name
  FROM public.business_memberships bm
  LEFT JOIN private.staff_identities si ON si.id = bm.staff_identity_id
  LEFT JOIN public.profiles p ON p.auth_user_id = bm.auth_user_id
  WHERE bm.id = NEW.actor_membership_id;

  v_body := CASE
    WHEN v_report IS NULL OR jsonb_typeof(v_report) <> 'object'
      THEN COALESCE(v_actor_name, 'Cashier') || ' closed a shift. Open the closing record for available details.'
    ELSE
      COALESCE(v_actor_name, 'Cashier') || ' closed shift. Sales ' || v_currency || ' ' ||
      round(COALESCE(NULLIF(v_report ->> 'gross_sales_minor', '')::numeric, 0) / power(10, v_decimals), v_decimals)::text ||
      ' (cash ' || round(COALESCE(NULLIF(v_report ->> 'cash_sales_minor', '')::numeric, 0) / power(10, v_decimals), v_decimals)::text ||
      ', online ' || round(COALESCE(NULLIF(v_report ->> 'online_sales_minor', '')::numeric, 0) / power(10, v_decimals), v_decimals)::text ||
      ', credit ' || round(COALESCE(NULLIF(v_report ->> 'credit_given_minor', '')::numeric, 0) / power(10, v_decimals), v_decimals)::text ||
      '); coupon top-ups ' || round(COALESCE(NULLIF(v_report ->> 'coupon_top_up_minor', '')::numeric, 0) / power(10, v_decimals), v_decimals)::text ||
      '; counted cash ' || round(COALESCE(NULLIF(v_report ->> 'counted_cash_minor', '')::numeric, 0) / power(10, v_decimals), v_decimals)::text ||
      '; cash difference ' || round(COALESCE(NULLIF(v_report ->> 'difference_minor', '')::numeric, 0) / power(10, v_decimals), v_decimals)::text || '.'
  END;

  FOR v_admin IN
    SELECT admin.id AS membership_id
    FROM public.business_memberships admin
    JOIN public.profiles profile ON profile.auth_user_id = admin.auth_user_id AND profile.is_active = true
    JOIN public.businesses business ON business.id = admin.business_id AND business.is_active = true
    JOIN public.branches branch ON branch.id = admin.branch_id AND branch.business_id = admin.business_id AND branch.is_active = true
    WHERE admin.business_id = NEW.business_id
      AND admin.branch_id = NEW.branch_id
      AND admin.role = 'ADMIN'
      AND admin.is_active = true
      AND admin.removed_at IS NULL
      AND admin.staff_identity_id IS NULL
  LOOP
    PERFORM private.enqueue_business_notification(
      'DAY_CLOSED:' || NEW.business_id || ':' || NEW.operation_id || ':' || v_admin.membership_id,
      NEW.business_id, NEW.branch_id, v_admin.membership_id,
      'DAY_CLOSED', 'Cashier day closing completed', v_body,
      jsonb_build_object(
        'operation_id', NEW.operation_id,
        'cashier_membership_id', NEW.actor_membership_id,
        'cashier_name', COALESCE(v_actor_name, 'Cashier'),
        'closed_at', NEW.occurred_at,
        'received_at', NEW.received_at,
        'branch_timezone_id', NEW.branch_timezone_id,
        'business_local_date', NEW.business_local_date,
        'report', COALESCE(v_report, '{}'::jsonb)
      )
    );
    IF length(v_difference_minor) BETWEEN 1 AND 19
       AND v_difference_minor ~ '^-?[0-9]+$'
       AND v_difference_minor !~ '[^0-9-]'
       AND position('-' in substring(v_difference_minor from 2)) = 0
       AND v_difference_minor <> '-' THEN
      IF v_difference_minor::numeric <> 0 THEN
        PERFORM private.enqueue_business_notification(
          'CASH_DIFFERENCE:' || NEW.business_id || ':' || NEW.operation_id || ':' || v_admin.membership_id,
          NEW.business_id, NEW.branch_id, v_admin.membership_id,
          'CASH_DISCREPANCY', 'Cash difference needs review',
          COALESCE(v_actor_name, 'Cashier') || ' closed a shift with a cash difference. Review the closing report.',
          jsonb_build_object(
            'operation_id', NEW.operation_id,
            'cashier_name', COALESCE(v_actor_name, 'Cashier'),
            'report', COALESCE(v_report, '{}'::jsonb)
          )
        );
      END IF;
    END IF;
  END LOOP;
  RETURN NEW;
END;
$function$;

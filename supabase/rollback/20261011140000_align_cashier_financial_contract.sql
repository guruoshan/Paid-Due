-- ROLLBACK for 20261011140000_align_cashier_financial_contract.sql
-- Restores the exact pre-migration live function definition captured before deployment.
-- Applying this intentionally restores the old behavior: no shift lifecycle allowlist
-- and no tender-category permission checks. Use only to roll back the migration.
-- Align cashier financial ingestion with the Android operation and tender contract.
-- Shift lifecycle events are valid for an active cashier membership. Sale tenders
-- are authorized from immutable canonical settlement evidence, not client UI state.
CREATE OR REPLACE FUNCTION public.ingest_cashier_financial_envelope(p_business_id text, p_operation_id text, p_branch_id text, p_actor_membership_id text, p_device_id text, p_operation_type text, p_schema_version integer, p_occurred_at timestamp with time zone, p_branch_timezone_id text, p_business_local_date date, p_canonical_text text, p_canonical_sha256 text, p_canonical_payload jsonb)
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
    SELECT m.*
    INTO v_membership
    FROM public.business_memberships m
    JOIN private.staff_identities s
      ON s.id = m.staff_identity_id
    JOIN public.businesses b
      ON b.id = m.business_id
    JOIN public.branches br
      ON br.id = m.branch_id
     AND br.business_id = m.business_id
    WHERE m.id = p_actor_membership_id
      AND m.business_id = p_business_id
      AND m.branch_id = p_branch_id
      AND m.role = 'CASHIER'
      AND m.is_active = true
      AND s.is_active = true
      AND b.is_active = true
      AND br.is_active = true;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'INVALID_CASHIER_MEMBERSHIP'
            USING ERRCODE = '42501';
    END IF;

    v_allowed := CASE p_operation_type
        WHEN 'RECORD_SALE'
            THEN v_membership.can_create_sale
        WHEN 'RECORD_CREDIT_REPAYMENT'
            THEN v_membership.can_create_credit_sale
        WHEN 'RECORD_COUPON_PURCHASE'
            THEN v_membership.can_use_coupon
        WHEN 'RECORD_REFUND'
            THEN v_membership.can_refund
        WHEN 'RECORD_VOID'
            THEN v_membership.can_void
        WHEN 'RECORD_AMOUNT_CORRECTION'
            THEN v_membership.can_correct_amount
        WHEN 'RECORD_PAYMENT_METHOD_CHANGE'
            THEN v_membership.can_change_payment_method
        ELSE false
    END;

    IF NOT COALESCE(v_allowed, false) THEN
        RAISE EXCEPTION 'OPERATION_NOT_PERMITTED'
            USING ERRCODE = '42501';
    END IF;

    IF p_schema_version IS NULL
       OR p_schema_version < 1
       OR p_device_id IS NULL
       OR length(btrim(p_device_id)) NOT BETWEEN 1 AND 128
       OR p_canonical_text IS NULL
       OR octet_length(p_canonical_text) NOT BETWEEN 2 AND 262144
       OR p_canonical_sha256 IS NULL
       OR p_canonical_sha256 !~ '^[0-9a-f]{64}$'
       OR p_canonical_payload IS NULL
       OR jsonb_typeof(p_canonical_payload) <> 'object'
    THEN
        RAISE EXCEPTION 'INVALID_ENVELOPE';
    END IF;

    IF p_canonical_sha256 <> encode(
        extensions.digest(
            convert_to(p_canonical_text, 'UTF8'),
            'sha256'
        ),
        'hex'
    ) THEN
        RAISE EXCEPTION 'DIGEST_MISMATCH';
    END IF;

    IF p_canonical_payload <> p_canonical_text::jsonb
       OR p_canonical_payload ->> 'operation_id'
           IS DISTINCT FROM p_operation_id
       OR p_canonical_payload ->> 'business_id'
           IS DISTINCT FROM p_business_id
    THEN
        RAISE EXCEPTION 'PAYLOAD_MISMATCH';
    END IF;

    INSERT INTO private.financial_operation_envelopes AS e (
        business_id, operation_id, branch_id,
        actor_membership_id, device_id, operation_type,
        schema_version, occurred_at, branch_timezone_id,
        business_local_date, canonical_text,
        canonical_sha256, canonical_payload
    )
    VALUES (
        p_business_id, p_operation_id, p_branch_id,
        v_membership.id, p_device_id, p_operation_type,
        p_schema_version, p_occurred_at, p_branch_timezone_id,
        p_business_local_date, p_canonical_text,
        p_canonical_sha256, p_canonical_payload
    )
    ON CONFLICT ON CONSTRAINT financial_operation_envelopes_pkey DO NOTHING
    RETURNING e.canonical_sha256 INTO v_inserted_hash;

    IF v_inserted_hash IS NOT NULL THEN
        RETURN QUERY SELECT
            'ACKNOWLEDGED'::text,
            p_operation_id,
            p_canonical_sha256,
            false;
        RETURN;
    END IF;

    SELECT e.canonical_sha256
    INTO v_existing_hash
    FROM private.financial_operation_envelopes e
    WHERE e.business_id = p_business_id
      AND e.operation_id = p_operation_id;

    IF v_existing_hash IS DISTINCT FROM p_canonical_sha256 THEN
        RAISE EXCEPTION 'OPERATION_COLLISION';
    END IF;

    RETURN QUERY SELECT
        'ACKNOWLEDGED'::text,
        p_operation_id,
        p_canonical_sha256,
        true;
END;
$function$

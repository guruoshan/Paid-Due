-- BOARDLOCK AUTH RATE LIMIT V1
-- STAGED ONLY: DO NOT APPLY WITHOUT APPROVAL.
-- No existing financial tables are modified.

BEGIN;

CREATE TABLE IF NOT EXISTS private.auth_rate_limits (
    bucket_key text PRIMARY KEY,
    window_started_at timestamptz NOT NULL,
    attempts integer NOT NULL CHECK (attempts >= 1),
    updated_at timestamptz NOT NULL DEFAULT now()
);

REVOKE ALL ON private.auth_rate_limits
FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.check_auth_rate_limit(
    p_scope text,
    p_bucket_key text
)
RETURNS TABLE (
    allowed boolean,
    retry_after_seconds integer
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_limit integer;
    v_window_seconds integer;
    v_attempts integer;
    v_started timestamptz;
    v_now timestamptz := clock_timestamp();
BEGIN
    IF p_scope = 'LOGIN' THEN
        v_limit := 5;
        v_window_seconds := 300;
    ELSIF p_scope = 'ACTIVATE' THEN
        v_limit := 5;
        v_window_seconds := 3600;
    ELSE
        RAISE EXCEPTION 'Invalid scope'
        USING ERRCODE = '22023';
    END IF;

    IF p_bucket_key IS NULL OR
       p_bucket_key !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Invalid bucket key'
        USING ERRCODE = '22023';
    END IF;

    INSERT INTO private.auth_rate_limits AS current_row (
        bucket_key,
        window_started_at,
        attempts,
        updated_at
    )
    VALUES (
        p_bucket_key,
        v_now,
        1,
        v_now
    )
    ON CONFLICT (bucket_key)
    DO UPDATE SET
        attempts = CASE
            WHEN current_row.window_started_at
                 <= v_now - make_interval(
                     secs => v_window_seconds
                 )
            THEN 1
            ELSE LEAST(
                current_row.attempts + 1,
                v_limit + 1
            )
        END,
        window_started_at = CASE
            WHEN current_row.window_started_at
                 <= v_now - make_interval(
                     secs => v_window_seconds
                 )
            THEN v_now
            ELSE current_row.window_started_at
        END,
        updated_at = v_now
    RETURNING
        current_row.attempts,
        current_row.window_started_at
    INTO v_attempts, v_started;

    RETURN QUERY SELECT
        v_attempts <= v_limit,
        CASE
            WHEN v_attempts <= v_limit THEN 0
            ELSE GREATEST(
                1,
                CEIL(
                    EXTRACT(EPOCH FROM (
                        v_started
                        + make_interval(
                            secs => v_window_seconds
                        )
                        - v_now
                    ))
                )::integer
            )
        END;
END;
$$;

REVOKE ALL ON FUNCTION
public.check_auth_rate_limit(text,text)
FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION
public.check_auth_rate_limit(text,text)
TO service_role;

COMMIT;

-- Operational requirement before release:
-- Schedule retention cleanup for expired buckets.
-- Do not delete or alter financial records.
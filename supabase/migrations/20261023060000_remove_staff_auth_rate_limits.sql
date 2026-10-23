-- The staff login and invitation rate limiter is no longer used after retiring cashier auth.
DROP FUNCTION IF EXISTS public.check_auth_rate_limit(text, text);
DROP TABLE IF EXISTS private.auth_rate_limits;

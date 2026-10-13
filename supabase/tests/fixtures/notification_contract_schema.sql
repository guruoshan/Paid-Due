-- Minimal synthetic dependencies for exercising notification migration against the financial contract.
CREATE SCHEMA IF NOT EXISTS auth;
-- Supabase's disposable PostgreSQL image supplies auth.uid(); the fixture does
-- not create or modify managed auth objects.
CREATE TABLE IF NOT EXISTS public.profiles (
  auth_user_id uuid UNIQUE,
  display_name text,
  is_active boolean NOT NULL DEFAULT true,
  local_user_id text,
  canonical_phone text,
  email text,
  language_code text,
  created_at timestamptz DEFAULT now(),
  updated_at timestamptz DEFAULT now()
);
ALTER TABLE public.businesses ADD COLUMN IF NOT EXISTS name text;
ALTER TABLE public.branches ADD COLUMN IF NOT EXISTS name text;
ALTER TABLE public.branches ADD COLUMN IF NOT EXISTS code text;
ALTER TABLE public.branches ADD COLUMN IF NOT EXISTS country_code text;
ALTER TABLE public.branches ADD COLUMN IF NOT EXISTS currency_code text;
ALTER TABLE public.branches ADD COLUMN IF NOT EXISTS currency_decimal_places integer;
ALTER TABLE public.branches ADD COLUMN IF NOT EXISTS currency_symbol text;
ALTER TABLE public.branches ADD COLUMN IF NOT EXISTS timezone_id text;
ALTER TABLE public.branches ADD COLUMN IF NOT EXISTS tax_rounding_mode text;
ALTER TABLE public.branches ADD COLUMN IF NOT EXISTS default_tax_rate_bps integer;
ALTER TABLE public.branches ADD COLUMN IF NOT EXISTS address text;
ALTER TABLE public.branches ADD COLUMN IF NOT EXISTS phone text;
ALTER TABLE public.branches ADD COLUMN IF NOT EXISTS tax_registration_number text;
ALTER TABLE public.business_memberships ADD COLUMN IF NOT EXISTS updated_at timestamptz DEFAULT now();
ALTER TABLE public.business_memberships ADD COLUMN IF NOT EXISTS can_issue_invoice boolean NOT NULL DEFAULT false;
ALTER TABLE public.business_memberships ADD COLUMN IF NOT EXISTS can_issue_receipt boolean NOT NULL DEFAULT false;
ALTER TABLE public.business_memberships ADD COLUMN IF NOT EXISTS can_view_broad_history boolean NOT NULL DEFAULT false;
ALTER TABLE public.business_memberships ADD COLUMN IF NOT EXISTS can_manage_products boolean NOT NULL DEFAULT false;
ALTER TABLE public.business_memberships ADD COLUMN IF NOT EXISTS can_view_reports boolean NOT NULL DEFAULT false;
ALTER TABLE private.staff_identities ADD COLUMN IF NOT EXISTS canonical_phone text;
ALTER TABLE private.staff_identities ADD COLUMN IF NOT EXISTS display_name text;
ALTER TABLE private.staff_identities ADD COLUMN IF NOT EXISTS credential_hash text;
ALTER TABLE private.staff_identities ADD COLUMN IF NOT EXISTS credential_salt text;

CREATE TABLE IF NOT EXISTS private.cashier_sync_sessions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  membership_id text NOT NULL,
  token_hash text,
  expires_at timestamptz NOT NULL DEFAULT now() + interval '1 day',
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS private.staff_admin_audit_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_auth_user_id uuid,
  membership_id text,
  business_id text,
  branch_id text,
  event_type text NOT NULL,
  before_state jsonb NOT NULL DEFAULT '{}'::jsonb,
  after_state jsonb NOT NULL DEFAULT '{}'::jsonb,
  occurred_at timestamptz NOT NULL DEFAULT now()
);

-- Minimal disposable schema contract for executing the real migration function.
-- This is synthetic test data/schema only; never run against a hosted project.
CREATE SCHEMA IF NOT EXISTS private;
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;

CREATE TABLE public.businesses (
  id text PRIMARY KEY,
  is_active boolean NOT NULL DEFAULT true
);
CREATE TABLE public.branches (
  id text PRIMARY KEY,
  business_id text NOT NULL REFERENCES public.businesses(id),
  name text NOT NULL DEFAULT 'Test Branch',
  code text NOT NULL DEFAULT 'TEST',
  country_code text NOT NULL DEFAULT 'OM',
  currency_code text NOT NULL DEFAULT 'OMR',
  currency_decimal_places integer NOT NULL DEFAULT 3,
  currency_symbol text NOT NULL DEFAULT 'OMR',
  timezone_id text NOT NULL DEFAULT 'UTC',
  tax_rounding_mode text NOT NULL DEFAULT 'HALF_UP',
  default_tax_rate_bps integer NOT NULL DEFAULT 0,
  address text,
  phone text,
  tax_registration_number text,
  is_active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE private.staff_identities (
  id uuid PRIMARY KEY,
  is_active boolean NOT NULL DEFAULT true
);
CREATE TABLE public.business_memberships (
  id text PRIMARY KEY,
  business_id text NOT NULL REFERENCES public.businesses(id),
  branch_id text NOT NULL REFERENCES public.branches(id),
  auth_user_id uuid,
  local_user_id text,
  staff_identity_id uuid REFERENCES private.staff_identities(id),
  removed_at timestamptz,
  role text NOT NULL,
  is_active boolean NOT NULL DEFAULT true,
  can_create_sale boolean NOT NULL DEFAULT false,
  can_accept_cash boolean NOT NULL DEFAULT false,
  can_accept_online boolean NOT NULL DEFAULT false,
  can_create_credit_sale boolean NOT NULL DEFAULT false,
  can_use_coupon boolean NOT NULL DEFAULT false,
  can_refund boolean NOT NULL DEFAULT false,
  can_void boolean NOT NULL DEFAULT false,
  can_correct_amount boolean NOT NULL DEFAULT false,
  can_change_payment_method boolean NOT NULL DEFAULT false,
  CONSTRAINT business_memberships_principal_role_check CHECK (
    (role = 'ADMIN' AND auth_user_id IS NOT NULL AND staff_identity_id IS NULL)
    OR (role = 'CASHIER' AND auth_user_id IS NULL AND staff_identity_id IS NOT NULL)
  )
);
CREATE TABLE private.financial_operation_envelopes (
  business_id text NOT NULL,
  operation_id text NOT NULL,
  branch_id text NOT NULL,
  actor_membership_id text NOT NULL,
  device_id text NOT NULL,
  operation_type text NOT NULL,
  schema_version integer NOT NULL,
  occurred_at timestamptz NOT NULL,
  branch_timezone_id text NOT NULL,
  business_local_date date NOT NULL,
  canonical_text text NOT NULL,
  canonical_sha256 text NOT NULL,
  canonical_payload jsonb NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT envelope_digest_valid CHECK (canonical_sha256 = encode(extensions.digest(convert_to(canonical_text, 'UTF8'), 'sha256'), 'hex')),
  CONSTRAINT envelope_identity_valid CHECK ((canonical_payload ->> 'operation_id') = operation_id AND (canonical_payload ->> 'business_id') = business_id),
  CONSTRAINT envelope_payload_valid CHECK (canonical_payload = canonical_text::jsonb),
  CONSTRAINT envelope_size_valid CHECK (octet_length(canonical_text) BETWEEN 2 AND 262144),
  CONSTRAINT financial_operation_envelopes_pkey PRIMARY KEY (business_id, operation_id)
);

INSERT INTO public.businesses (id) VALUES ('biz-test');
INSERT INTO public.branches (id, business_id) VALUES ('branch-test', 'biz-test');
INSERT INTO private.staff_identities (id) VALUES ('11111111-1111-4111-8111-111111111111');
INSERT INTO public.business_memberships (
  id, business_id, branch_id, staff_identity_id, role, is_active,
  can_create_sale, can_accept_cash, can_accept_online,
  can_create_credit_sale, can_use_coupon, can_refund, can_void,
  can_correct_amount, can_change_payment_method
) VALUES (
  'membership-test', 'biz-test', 'branch-test', '11111111-1111-4111-8111-111111111111', 'CASHIER', true,
  true, true, true, true, true, true, true, true, true
);

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
  is_active boolean NOT NULL DEFAULT true
);
CREATE TABLE private.staff_identities (
  id text PRIMARY KEY,
  is_active boolean NOT NULL DEFAULT true
);
CREATE TABLE public.business_memberships (
  id text PRIMARY KEY,
  business_id text NOT NULL REFERENCES public.businesses(id),
  branch_id text NOT NULL REFERENCES public.branches(id),
  staff_identity_id text NOT NULL REFERENCES private.staff_identities(id),
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
  can_change_payment_method boolean NOT NULL DEFAULT false
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
  branch_timezone_id text,
  business_local_date date,
  canonical_text text NOT NULL,
  canonical_sha256 text NOT NULL,
  canonical_payload jsonb NOT NULL,
  CONSTRAINT financial_operation_envelopes_pkey PRIMARY KEY (business_id, operation_id)
);

INSERT INTO public.businesses (id) VALUES ('biz-test');
INSERT INTO public.branches (id, business_id) VALUES ('branch-test', 'biz-test');
INSERT INTO private.staff_identities (id) VALUES ('staff-test');
INSERT INTO public.business_memberships (
  id, business_id, branch_id, staff_identity_id, role, is_active,
  can_create_sale, can_accept_cash, can_accept_online,
  can_create_credit_sale, can_use_coupon, can_refund, can_void,
  can_correct_amount, can_change_payment_method
) VALUES (
  'membership-test', 'biz-test', 'branch-test', 'staff-test', 'CASHIER', true,
  true, true, true, true, true, true, true, true, true
);

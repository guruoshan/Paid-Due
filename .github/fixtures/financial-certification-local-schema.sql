-- LOCAL CERTIFICATION BASE SCHEMA ONLY.
-- The repository's production migration chain contains incremental RPC changes and assumes
-- pre-existing business tables; this isolated schema supplies only those table contracts
-- required by the real AuthRepository/RemoteFinancialSyncRepository path. Never deploy this file.
CREATE SCHEMA IF NOT EXISTS private;
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;

CREATE TABLE public.businesses (
    id text PRIMARY KEY,
    name text NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.branches (
    id text PRIMARY KEY,
    business_id text NOT NULL REFERENCES public.businesses(id) ON DELETE RESTRICT,
    name text NOT NULL,
    code text NOT NULL,
    country_code text NOT NULL DEFAULT 'OM',
    currency_code text NOT NULL DEFAULT 'OMR',
    currency_decimal_places integer NOT NULL DEFAULT 3,
    currency_symbol text NOT NULL DEFAULT 'OMR',
    timezone_id text NOT NULL DEFAULT 'Asia/Muscat',
    tax_rounding_mode text NOT NULL DEFAULT 'HALF_UP',
    default_tax_rate_bps integer NOT NULL DEFAULT 500,
    address text,
    phone text,
    tax_registration_number text,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (business_id, id),
    UNIQUE (business_id, code)
);

CREATE TABLE public.profiles (
    auth_user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email text,
    display_name text NOT NULL,
    canonical_phone text,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.business_memberships (
    id text PRIMARY KEY,
    business_id text NOT NULL REFERENCES public.businesses(id) ON DELETE RESTRICT,
    branch_id text NOT NULL,
    auth_user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
    local_user_id text,
    staff_identity_id uuid,
    role text NOT NULL CHECK (role IN ('ADMIN', 'CASHIER')),
    is_active boolean NOT NULL DEFAULT true,
    can_create_sale boolean NOT NULL DEFAULT false,
    can_accept_cash boolean NOT NULL DEFAULT false,
    can_accept_online boolean NOT NULL DEFAULT false,
    can_create_credit_sale boolean NOT NULL DEFAULT false,
    can_use_coupon boolean NOT NULL DEFAULT false,
    can_issue_invoice boolean NOT NULL DEFAULT false,
    can_issue_receipt boolean NOT NULL DEFAULT false,
    can_refund boolean NOT NULL DEFAULT false,
    can_void boolean NOT NULL DEFAULT false,
    can_correct_amount boolean NOT NULL DEFAULT false,
    can_change_payment_method boolean NOT NULL DEFAULT false,
    can_view_broad_history boolean NOT NULL DEFAULT false,
    can_manage_products boolean NOT NULL DEFAULT false,
    can_view_reports boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    removed_at timestamptz,
    UNIQUE (business_id, id),
    UNIQUE (business_id, branch_id, id),
    FOREIGN KEY (business_id, branch_id)
        REFERENCES public.branches(business_id, id) ON DELETE RESTRICT
);

CREATE TABLE private.financial_operation_envelopes (
    business_id text NOT NULL REFERENCES public.businesses(id) ON DELETE RESTRICT,
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
    PRIMARY KEY (business_id, operation_id),
    FOREIGN KEY (business_id, branch_id)
        REFERENCES public.branches(business_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (business_id, actor_membership_id)
        REFERENCES public.business_memberships(business_id, id) ON DELETE RESTRICT
);

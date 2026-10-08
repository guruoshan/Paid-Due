-- BOARDLOCK STAGING ONLY — DO NOT APPLY TO PRODUCTION
-- Private inert envelope table. This is NOT an ingest endpoint and does NOT
-- implement complete financial synchronization.
begin;
create schema if not exists private;
create table if not exists private.financial_operation_envelopes (
    operation_id text primary key,
    business_id text not null references public.businesses(id) on delete restrict,
    branch_id text not null references public.branches(id) on delete restrict,
    actor_membership_id text not null references public.business_memberships(id) on delete restrict,
    device_id text not null check (length(btrim(device_id)) > 0),
    operation_type text not null check (length(btrim(operation_type)) > 0),
    schema_version integer not null check (schema_version > 0),
    occurred_at timestamptz not null,
    canonical_sha256 text not null check (canonical_sha256 ~ '^[0-9a-f]{64}$'),
    canonical_payload jsonb not null check (jsonb_typeof(canonical_payload) = 'object'),
    received_at timestamptz not null default now()
);
alter table private.financial_operation_envelopes enable row level security;
revoke all on private.financial_operation_envelopes from public, anon, authenticated;
create index if not exists idx_financial_envelopes_branch_period
    on private.financial_operation_envelopes(business_id,branch_id,occurred_at,operation_id);
-- No client policy, ingest procedure, mutation or reporting endpoint.
commit;

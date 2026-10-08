-- BOARDLOCK STAGED SQL — FOUNDATION ONLY. DO NOT APPLY OR AUTO-DEPLOY.
-- Deliberately NOT in supabase/migrations. No ingest RPC, no financial write.
begin;
create extension if not exists pgcrypto with schema extensions;
alter table public.branches add constraint branches_business_id_id_unique unique (business_id, id);
alter table public.business_memberships add constraint memberships_business_id_id_unique unique (business_id, id);
alter table public.business_memberships add constraint memberships_business_branch_id_unique unique (business_id, branch_id, id);

create table private.financial_operation_envelopes (
    business_id text not null references public.businesses(id) on delete restrict,
    operation_id text not null,
    branch_id text not null,
    actor_membership_id text not null,
    device_id text not null check (length(btrim(device_id)) between 1 and 128),
    operation_type text not null check (operation_type in (
        'RECORD_SALE','RECORD_CREDIT_REPAYMENT','RECORD_COUPON_PURCHASE',
        'RECORD_AMOUNT_CORRECTION','RECORD_PAYMENT_METHOD_CHANGE','RECORD_VOID',
        'RECORD_REFUND','RECORD_EXPENSE','RECORD_EXPENSE_CORRECTION',
        'RECORD_EXPENSE_VOID','OPEN_CASH_SHIFT','COUNT_CASH_SHIFT','CLOSE_CASH_SHIFT'
    )),
    schema_version integer not null check (schema_version > 0),
    occurred_at timestamptz not null,
    branch_timezone_id text not null check (length(btrim(branch_timezone_id)) between 1 and 100),
    business_local_date date not null,
    canonical_text text not null check (octet_length(canonical_text) between 2 and 262144),
    canonical_sha256 text not null check (canonical_sha256 ~ '^[0-9a-f]{64}$'),
    canonical_payload jsonb not null check (jsonb_typeof(canonical_payload) = 'object'),
    received_at timestamptz not null default now(),
    primary key (business_id, operation_id),
    foreign key (business_id, branch_id) references public.branches(business_id, id) on delete restrict,
    foreign key (business_id, branch_id, actor_membership_id)
        references public.business_memberships(business_id, branch_id, id) on delete restrict,
    constraint canonical_text_digest check (
        canonical_sha256 = encode(extensions.digest(convert_to(canonical_text, 'UTF8'), 'sha256'),'hex')
    ),
    constraint canonical_payload_text check (canonical_payload = canonical_text::jsonb),
    constraint canonical_operation_identity check (canonical_payload ->> 'operation_id' = operation_id),
    constraint canonical_business_identity check (canonical_payload ->> 'business_id' = business_id)
);
create index financial_envelope_branch_period_idx
    on private.financial_operation_envelopes(business_id, branch_id, business_local_date, operation_id);
alter table private.financial_operation_envelopes enable row level security;
revoke all on private.financial_operation_envelopes from public, anon, authenticated;

create function private.reject_financial_envelope_row_mutation()
returns trigger language plpgsql
set search_path = ''
as $$
begin
    raise exception 'Financial envelope is immutable' using errcode = 'P0001';
end;
$$;
create trigger reject_financial_envelope_update_delete
    before update or delete on private.financial_operation_envelopes
    for each row execute function private.reject_financial_envelope_row_mutation();
create function private.reject_financial_envelope_truncate()
returns trigger language plpgsql
set search_path = ''
as $$
begin
    raise exception 'Financial envelope truncate forbidden' using errcode = 'P0001';
end;
$$;
create trigger reject_financial_envelope_truncate
    before truncate on private.financial_operation_envelopes
    for each statement execute function private.reject_financial_envelope_truncate();
commit;

-- VERIFICATION (manual, non-production test database only):
-- SELECT rolname, has_schema_privilege(rolname,'private','USAGE') AS schema_usage,
--        has_table_privilege(rolname,'private.financial_operation_envelopes','SELECT') AS can_select,
--        has_table_privilege(rolname,'private.financial_operation_envelopes','INSERT') AS can_insert,
--        has_table_privilege(rolname,'private.financial_operation_envelopes','UPDATE') AS can_update
-- FROM pg_roles WHERE rolname IN ('anon','authenticated');
-- Under service_role with TEST ROW seeded and transaction ROLLBACK:
-- UPDATE private.financial_operation_envelopes SET device_id='x' WHERE operation_id='<test>';
-- DELETE FROM private.financial_operation_envelopes WHERE operation_id='<test>';
-- TRUNCATE private.financial_operation_envelopes;
-- EACH MUST ERROR. Note: superuser/DDL owner can remove triggers: privileged
-- administrative tampering is outside trigger protection.
-- Check pg_get_constraintdef for both new composite foreign keys.
-- This staged schema is not deployable as-is without security review of extension
-- availability, canonicalization, fixed timezone/date consistency and supported types.

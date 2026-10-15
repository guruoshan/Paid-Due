# Supabase migration source of truth

The historical migration files in this folder are aligned to the version/name records in the live project's `supabase_migrations.schema_migrations` ledger. The customer-directory migration timestamps now match the applied production versions, and historical migration SQL has been restored from the deployed ledger. The forward-only `20261014150651_capture_live_function_definitions.sql` migration captures a read-only RPC that had been deployed outside the recorded history and reconciles the deployed financial-ingest function definition. Apply it only after its isolated SQL gate passes, then verify the live migration list before any further push.

Before any production push, compare `supabase migration list` against the live project and require every applied version to be represented locally. Do not delete deployed functions, policies, tables, or RPCs merely to clear a linter warning; review their callers and security boundaries first.

## Bootstrap limitation

This migration history begins on 2026-10-08 and assumes core business, branch, profile, membership, and financial-envelope schema already exists. The repository still does **not** contain a complete clean-project baseline schema dump. Do not treat this migration folder alone as sufficient to create a new Supabase project. A schema-only baseline must be captured and reviewed before supporting fresh-project bootstrap.

## Historical data-reset migration

`20261012104437_authorized_production_data_reset_20261012.sql` was a one-time production data reset and is represented here by a safe no-op. Its exact destructive SQL is archived under `supabase/archive/production-only-migrations/` for audit only. Never execute the archived SQL manually or put it back in the active migration path.

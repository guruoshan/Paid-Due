-- Historical production-only data reset, applied on 2026-10-12.
-- The original destructive statements are preserved under
-- supabase/archive/production-only-migrations/20261012104437_authorized_production_data_reset_20261012.sql.
-- They are intentionally NOT replayed by fresh installs or ordinary deployments.
-- The original statements had no lasting schema effect; this safe no-op preserves
-- the already-applied migration version without risking another data deletion.
SELECT 1;

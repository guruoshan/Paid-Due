-- SOURCE RECONCILIATION ONLY. NOT A MIGRATION. DO NOT APPLY.
-- Recorded from production catalog on 2026-11-07, normalised body SHA-256 ea50a08e96e3b58836dd8778cdebc980cf343acd1786dd0a8a561ce3f2288615.
-- Applied out of band before this repository tracked it. Migration ledger status: see reconciliation doc.

CREATE OR REPLACE FUNCTION private.touch_updated_at()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
begin
    new.updated_at = now();
    return new;
end;
$function$

-- -----------------------------------------------------------------------------
-- RECORDED TRIGGER DEFINITIONS ONLY. DO NOT APPLY.
-- CREATE TRIGGER trg_branches_touch_updated_at BEFORE UPDATE ON branches FOR EACH ROW EXECUTE FUNCTION private.touch_updated_at()
-- CREATE TRIGGER trg_businesses_touch_updated_at BEFORE UPDATE ON businesses FOR EACH ROW EXECUTE FUNCTION private.touch_updated_at()
-- CREATE TRIGGER trg_memberships_touch_updated_at BEFORE UPDATE ON business_memberships FOR EACH ROW EXECUTE FUNCTION private.touch_updated_at()
-- CREATE TRIGGER trg_profiles_touch_updated_at BEFORE UPDATE ON profiles FOR EACH ROW EXECUTE FUNCTION private.touch_updated_at()
-- CREATE TRIGGER trg_staff_identities_touch_updated_at BEFORE UPDATE ON private.staff_identities FOR EACH ROW EXECUTE FUNCTION private.touch_updated_at()

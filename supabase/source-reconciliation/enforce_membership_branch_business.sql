-- SOURCE RECONCILIATION ONLY. NOT A MIGRATION. DO NOT APPLY.
-- Recorded from production catalog on 2026-11-07, normalised body SHA-256 5e514eec1f9eceffbc60f1bec5d8c1a9bc04f62fa95dbe27c7a58d20093d9671.
-- Applied before this repository tracked it. Migration ledger status: no matching function-definition migration entry found in the live ledger; applied out of band before repository source tracking.

CREATE OR REPLACE FUNCTION private.enforce_membership_branch_business()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    branch_business_id text;
begin
    select b.business_id
      into branch_business_id
      from public.branches b
     where b.id = new.branch_id;

    if branch_business_id is null then
        raise exception 'Branch does not exist';
    end if;

    if branch_business_id <> new.business_id then
        raise exception 'Branch does not belong to membership business';
    end if;

    return new;
end;
$function$

-- -----------------------------------------------------------------------------
-- RECORDED TRIGGER DEFINITIONS ONLY. DO NOT APPLY.
-- CREATE TRIGGER trg_membership_branch_business BEFORE INSERT OR UPDATE ON business_memberships FOR EACH ROW EXECUTE FUNCTION private.enforce_membership_branch_business()

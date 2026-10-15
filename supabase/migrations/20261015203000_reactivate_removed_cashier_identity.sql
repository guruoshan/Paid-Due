-- Allow a previously removed cashier to accept a fresh invitation using the
-- same phone identity, but only when that identity has no active memberships.
-- Preserve old membership/audit rows and financial history. A new membership is
-- created from the new invite's permissions, and the PIN verifier is replaced
-- only for an identity that is fully removed.
DO $migration$
DECLARE
  v_definition text;
  v_start integer;
  v_end integer;
  v_return_start integer;
  v_return_end integer;
  v_existing_block text;
  v_return_block text;
  v_new_block text;
  v_ids_old text := $old$
    v_staff_identity_id := gen_random_uuid();
    v_membership_id := gen_random_uuid()::text;
    v_local_user_id := gen_random_uuid()::text;
    v_activated_at := now();
$old$;
  v_ids_new text := $new$
    IF v_staff_identity_id IS NULL THEN
      v_staff_identity_id := gen_random_uuid();
      v_membership_id := gen_random_uuid()::text;
      v_local_user_id := gen_random_uuid()::text;
      v_activated_at := now();
    END IF;
$new$;
  v_insert_start integer;
  v_insert_end integer;
  v_insert_block text;
  v_insert_new text;
BEGIN
  v_definition := replace(
    pg_catalog.pg_get_functiondef(
      'public.activate_staff_invite(text,text,text)'::regprocedure
    ),
    E'\r\n', E'\n'
  );

  v_start := strpos(v_definition,
    '    if found then' || E'\n\n        return query\n        select\n            ''PHONE_ALREADY_REGISTERED''::text,');
  v_end := strpos(v_definition,
    '    -- --------------------------------------------------------------' || E'\n    -- AUTHORITATIVE IDS');
  IF v_start = 0 OR v_end = 0 OR v_end <= v_start THEN
    RAISE EXCEPTION 'Could not locate existing phone-conflict activation block; migration aborted';
  END IF;

  v_existing_block := substring(v_definition FROM v_start FOR v_end - v_start);
  v_return_start := strpos(v_existing_block, E'\n        return query');
  v_return_end := strpos(v_existing_block, E'\n        return;\n    end if;') + length(E'\n        return;');
  IF v_return_start = 0 OR v_return_end <= v_return_start THEN
    RAISE EXCEPTION 'Could not isolate existing phone-conflict response; migration aborted';
  END IF;
  v_return_block := substring(v_existing_block FROM v_return_start + 1 FOR v_return_end - v_return_start);

  v_new_block :=
    '    if found then' || E'\n' ||
    '      if exists (' || E'\n' ||
    '        select 1 from public.business_memberships bm' || E'\n' ||
    '        where bm.staff_identity_id = v_existing_staff.id' || E'\n' ||
    '          and bm.is_active = true and bm.removed_at is null' || E'\n' ||
    '      ) then' || E'\n' ||
    v_return_block || E'\n' ||
    '      end if;' || E'\n' ||
    '      select bm.local_user_id into v_local_user_id' || E'\n' ||
    '      from public.business_memberships bm' || E'\n' ||
    '      where bm.staff_identity_id = v_existing_staff.id' || E'\n' ||
    '        and bm.local_user_id is not null' || E'\n' ||
    '      order by bm.updated_at desc nulls last, bm.created_at desc' || E'\n' ||
    '      limit 1;' || E'\n' ||
    '      v_staff_identity_id := v_existing_staff.id;' || E'\n' ||
    '      v_membership_id := gen_random_uuid()::text;' || E'\n' ||
    '      v_local_user_id := coalesce(v_local_user_id, gen_random_uuid()::text);' || E'\n' ||
    '      v_activated_at := now();' || E'\n' ||
    '    end if;' || E'\n\n';

  v_definition := substring(v_definition FROM 1 FOR v_start - 1)
    || v_new_block || substring(v_definition FROM v_end);

  IF strpos(v_definition, v_ids_old) = 0 THEN
    RAISE EXCEPTION 'Could not locate authoritative ID generation block; migration aborted';
  END IF;
  v_definition := replace(v_definition, v_ids_old, v_ids_new);

  v_insert_start := strpos(v_definition, '    insert into private.staff_identities (');
  v_insert_end := v_insert_start - 1 + strpos(
    substring(v_definition FROM v_insert_start),
    '    -- --------------------------------------------------------------' || E'\n    -- CASHIER MEMBERSHIP'
  );
  IF v_insert_start = 0 OR v_insert_end = 0 OR v_insert_end <= v_insert_start THEN
    RAISE EXCEPTION 'Could not locate staff identity insertion block; migration aborted';
  END IF;
  v_insert_block := substring(v_definition FROM v_insert_start FOR v_insert_end - v_insert_start);
  v_insert_new :=
    '    if v_existing_staff.id is null then' || E'\n' ||
    v_insert_block ||
    '    else' || E'\n' ||
    '      update private.staff_identities' || E'\n' ||
    '         set canonical_phone = v_invite.canonical_phone,' || E'\n' ||
    '             display_name = trim(v_invite.display_name),' || E'\n' ||
    '             credential_hash = p_credential_hash,' || E'\n' ||
    '             credential_salt = p_credential_salt,' || E'\n' ||
    '             is_active = true,' || E'\n' ||
    '             updated_at = v_activated_at' || E'\n' ||
    '       where id = v_staff_identity_id;' || E'\n' ||
    '    end if;' || E'\n\n';

  v_definition := substring(v_definition FROM 1 FOR v_insert_start - 1)
    || v_insert_new || substring(v_definition FROM v_insert_end);

  EXECUTE v_definition;
END
$migration$;

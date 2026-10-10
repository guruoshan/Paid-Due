DO $block$
DECLARE
  v_definition text;
  v_old text := '^pbkdf2-sha256:100000:[0-9a-f]{64}$';
  v_new text := '^pbkdf2-sha256:(100000|600000):[0-9a-f]{64}$';
BEGIN
  SELECT pg_catalog.pg_get_functiondef(
    'public.activate_staff_invite(text,text,text)'::regprocedure
  ) INTO v_definition;

  IF position(v_old IN v_definition) = 0 THEN
    RAISE EXCEPTION 'Expected PIN verifier validation was not found; refusing to patch activation function';
  END IF;

  v_definition := replace(v_definition, v_old, v_new);
  EXECUTE v_definition;
END;
$block$;
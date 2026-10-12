\set ON_ERROR_STOP on
INSERT INTO auth.users(id) VALUES ('00000000-0000-4000-8000-000000000101')
ON CONFLICT (id) DO NOTHING;
INSERT INTO public.profiles(auth_user_id,is_active,display_name)
VALUES ('00000000-0000-4000-8000-000000000101',true,'Test Admin')
ON CONFLICT (auth_user_id) DO UPDATE SET is_active=true;
INSERT INTO public.business_memberships(id,business_id,branch_id,role,auth_user_id,staff_identity_id)
VALUES ('admin-a','business-a','branch-a','ADMIN','00000000-0000-4000-8000-000000000101',NULL)
ON CONFLICT (id) DO NOTHING;

SELECT set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000000101',false);

DO $test$
DECLARE
  v_customer record;
BEGIN
  SELECT * INTO v_customer FROM public.sync_customer_directory_admin_upsert(
    'business-a','branch-a','admin-local-id','+96890000101','Admin Shared Customer',now()
  );
  IF v_customer.customer_id <> 'admin-local-id' OR NOT v_customer.was_created THEN
    RAISE EXCEPTION 'Admin upsert did not return canonical customer';
  END IF;

  IF (SELECT count(*) FROM public.sync_customer_directory_admin_list(
    'business-a','branch-a',NULL,100
  )) < 1 THEN
    RAISE EXCEPTION 'Admin could not list the shared business customer directory';
  END IF;

  BEGIN
    PERFORM * FROM public.sync_customer_directory_admin_list('business-b','branch-b',NULL,100);
    RAISE EXCEPTION 'Cross-business/branch Admin access unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN
    NULL;
  END;
END;
$test$;

SELECT 'PASS: authenticated Admin customer sync is scoped to the exact business and branch';

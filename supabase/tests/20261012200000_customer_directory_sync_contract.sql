\set ON_ERROR_STOP on
insert into public.businesses(id) values ('business-a'), ('business-b');
insert into public.branches(id,business_id) values ('branch-a','business-a'), ('branch-b','business-b');
insert into public.business_memberships(id,business_id,branch_id,role)
values ('cashier-a','business-a','branch-a','CASHIER'), ('cashier-b','business-b','branch-b','CASHIER');

do $$
declare
  r record;
  duplicate record;
  listed integer;
begin
  select * into r from public.sync_customer_directory_upsert(
    'business-a','branch-a','cashier-a','local-customer-1','+96891234567','Customer One',now()
  );
  if r.customer_id <> 'local-customer-1' or not r.was_created then
    raise exception 'first customer insert did not return canonical identity';
  end if;

  select * into duplicate from public.sync_customer_directory_upsert(
    'business-a','branch-a','cashier-a','different-device-id','+96891234567','Different Name',now()
  );
  if duplicate.customer_id <> 'local-customer-1' or duplicate.was_created then
    raise exception 'duplicate phone did not preserve first canonical identity';
  end if;
  if duplicate.display_name <> 'Customer One' then
    raise exception 'duplicate phone silently renamed the customer';
  end if;

  select count(*) into listed
  from public.sync_customer_directory_list('business-a','branch-a','cashier-a',null,100);
  if listed <> 1 then raise exception 'customer directory list count mismatch: %', listed; end if;

  begin
    perform * from public.sync_customer_directory_list('business-a','branch-b','cashier-a',null,100);
    raise exception 'cross-business/branch membership unexpectedly authorized';
  exception when insufficient_privilege then
    null;
  end;
end $$;

do $$
begin
  if not (select relrowsecurity from pg_class where oid='private.customer_directory'::regclass) then
    raise exception 'RLS is not enabled on private.customer_directory';
  end if;
  if has_table_privilege('authenticated','private.customer_directory','select') then
    raise exception 'authenticated role unexpectedly has direct customer-directory table access';
  end if;
end $$;

select 'PASS: customer directory insert, idempotent phone identity, stable name, scoped list, RLS and direct-table access checks';

create or replace function public.update_cashier_permissions(
  p_membership_id text,
  p_permissions jsonb
)
returns table(membership_id text, updated_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_uid uuid := (select auth.uid());
  v_target public.business_memberships%rowtype;
  v_updated timestamptz;
begin
  if v_uid is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;

  if p_permissions is null or jsonb_typeof(p_permissions) <> 'object' then
    raise exception 'Invalid permissions' using errcode = 'P0001';
  end if;

  if exists (
    select 1 from jsonb_object_keys(p_permissions) as k(key)
    where k.key not in (
      'CREATE_SALE','ACCEPT_CASH','ACCEPT_ONLINE','CREATE_CREDIT_SALE','USE_COUPON',
      'ISSUE_INVOICE','ISSUE_RECEIPT','REFUND','VOID','CORRECT_AMOUNT',
      'CHANGE_PAYMENT_METHOD','VIEW_BROAD_HISTORY','MANAGE_PRODUCTS','VIEW_REPORTS'
    )
  ) then
    raise exception 'Unknown permission' using errcode = 'P0001';
  end if;

  if exists (
    select 1 from jsonb_each(p_permissions) as e(key, value)
    where jsonb_typeof(e.value) <> 'boolean'
  ) then
    raise exception 'Permission values must be boolean' using errcode = 'P0001';
  end if;

  select m.* into v_target
  from public.business_memberships m
  where m.id = p_membership_id and m.role = 'CASHIER'
  for update;

  if not found then
    raise exception 'Cashier membership not found' using errcode = 'P0001';
  end if;

  -- Caller must be an ACTIVE ADMIN of this exact business and branch.
  if not exists (
    select 1 from public.business_memberships a
    where a.auth_user_id = v_uid
      and a.role = 'ADMIN'
      and a.is_active
      and a.business_id = v_target.business_id
      and a.branch_id = v_target.branch_id
  ) then
    raise exception 'Admin authority required' using errcode = '42501';
  end if;

  update public.business_memberships m set
    can_create_sale           = coalesce((p_permissions ->> 'CREATE_SALE')::boolean, m.can_create_sale),
    can_accept_cash           = coalesce((p_permissions ->> 'ACCEPT_CASH')::boolean, m.can_accept_cash),
    can_accept_online         = coalesce((p_permissions ->> 'ACCEPT_ONLINE')::boolean, m.can_accept_online),
    can_create_credit_sale    = coalesce((p_permissions ->> 'CREATE_CREDIT_SALE')::boolean, m.can_create_credit_sale),
    can_use_coupon            = coalesce((p_permissions ->> 'USE_COUPON')::boolean, m.can_use_coupon),
    can_issue_invoice         = coalesce((p_permissions ->> 'ISSUE_INVOICE')::boolean, m.can_issue_invoice),
    can_issue_receipt         = coalesce((p_permissions ->> 'ISSUE_RECEIPT')::boolean, m.can_issue_receipt),
    can_refund                = coalesce((p_permissions ->> 'REFUND')::boolean, m.can_refund),
    can_void                  = coalesce((p_permissions ->> 'VOID')::boolean, m.can_void),
    can_correct_amount        = coalesce((p_permissions ->> 'CORRECT_AMOUNT')::boolean, m.can_correct_amount),
    can_change_payment_method = coalesce((p_permissions ->> 'CHANGE_PAYMENT_METHOD')::boolean, m.can_change_payment_method),
    can_view_broad_history    = coalesce((p_permissions ->> 'VIEW_BROAD_HISTORY')::boolean, m.can_view_broad_history),
    can_manage_products       = coalesce((p_permissions ->> 'MANAGE_PRODUCTS')::boolean, m.can_manage_products),
    can_view_reports          = coalesce((p_permissions ->> 'VIEW_REPORTS')::boolean, m.can_view_reports),
    updated_at                = now()
  where m.id = p_membership_id
  returning m.updated_at into v_updated;

  return query select p_membership_id, v_updated;
end;
$function$;

create or replace function public.update_branch_details(
  p_business_id text,
  p_branch_id text,
  p_business_name text default null,
  p_address text default null,
  p_phone text default null,
  p_tax_registration_number text default null
)
returns table(business_id text, branch_id text, updated_at timestamptz)
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_uid uuid := (select auth.uid());
  v_updated timestamptz;
begin
  if v_uid is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;

  if not exists (
    select 1 from public.business_memberships a
    where a.auth_user_id = v_uid and a.role = 'ADMIN' and a.is_active
      and a.business_id = p_business_id and a.branch_id = p_branch_id
  ) then
    raise exception 'Admin authority required' using errcode = '42501';
  end if;

  if not exists (
    select 1 from public.branches br
    where br.id = p_branch_id and br.business_id = p_business_id
  ) then
    raise exception 'Invalid business or branch' using errcode = 'P0001';
  end if;

  if p_business_name is not null
     and length(btrim(p_business_name)) not between 1 and 200 then
    raise exception 'Invalid business name' using errcode = 'P0001';
  end if;
  if p_address is not null and length(p_address) > 500 then
    raise exception 'Address too long' using errcode = 'P0001';
  end if;
  if p_phone is not null and length(p_phone) > 50 then
    raise exception 'Phone too long' using errcode = 'P0001';
  end if;
  if p_tax_registration_number is not null and length(p_tax_registration_number) > 100 then
    raise exception 'Tax registration number too long' using errcode = 'P0001';
  end if;

  if p_business_name is not null then
    update public.businesses b
       set name = btrim(p_business_name), updated_at = now()
     where b.id = p_business_id;
  end if;

  -- Branch fields are always overwritten (blank becomes NULL) so the admin can clear them.
  update public.branches br
     set address = nullif(btrim(coalesce(p_address, '')), ''),
         phone = nullif(btrim(coalesce(p_phone, '')), ''),
         tax_registration_number = nullif(btrim(coalesce(p_tax_registration_number, '')), ''),
         updated_at = now()
   where br.id = p_branch_id and br.business_id = p_business_id
   returning br.updated_at into v_updated;

  return query select p_business_id, p_branch_id, v_updated;
end;
$function$;

revoke all on function public.update_cashier_permissions(text, jsonb) from public, anon;
grant execute on function public.update_cashier_permissions(text, jsonb) to authenticated;
revoke all on function public.update_branch_details(text, text, text, text, text, text) from public, anon;
grant execute on function public.update_branch_details(text, text, text, text, text, text) to authenticated;

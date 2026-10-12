-- Server-authoritative customer directory contract.
-- Customer identity is business-scoped and canonical-phone unique.
-- Clients must reconcile returned canonical IDs with local customer references before enabling rollout.
create table if not exists private.customer_directory (
  business_id text not null references public.businesses(id) on delete restrict,
  canonical_phone text not null,
  customer_id text not null,
  display_name text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  created_by_membership_id text not null references public.business_memberships(id) on delete restrict,
  updated_by_membership_id text not null references public.business_memberships(id) on delete restrict,
  primary key (business_id, canonical_phone),
  unique (business_id, customer_id),
  constraint customer_directory_phone_format check (canonical_phone ~ '^\+[1-9][0-9]{7,14}$'),
  constraint customer_directory_name_length check (length(btrim(display_name)) between 1 and 200),
  constraint customer_directory_id_length check (length(btrim(customer_id)) between 1 and 128)
);

alter table private.customer_directory enable row level security;
revoke all on table private.customer_directory from public, anon, authenticated;
grant select, insert, update on table private.customer_directory to service_role;

create index if not exists customer_directory_business_updated_idx
  on private.customer_directory (business_id, updated_at, canonical_phone);

create or replace function public.sync_customer_directory_upsert(
  p_business_id text,
  p_branch_id text,
  p_membership_id text,
  p_customer_id text,
  p_canonical_phone text,
  p_display_name text,
  p_created_at timestamptz default now()
)
returns table (
  customer_id text,
  canonical_phone text,
  display_name text,
  created_at timestamptz,
  updated_at timestamptz,
  was_created boolean
)
language plpgsql
security definer
set search_path to ''
as $function$
declare
  v_membership public.business_memberships%rowtype;
  v_row private.customer_directory%rowtype;
  v_inserted boolean := false;
begin
  if p_business_id is null or p_branch_id is null or p_membership_id is null
     or p_customer_id is null or length(btrim(p_customer_id)) not between 1 and 128
     or p_canonical_phone is null or p_canonical_phone !~ '^\+[1-9][0-9]{7,14}$'
     or p_display_name is null or length(btrim(p_display_name)) not between 1 and 200 then
    raise exception 'INVALID_CUSTOMER' using errcode = '22023';
  end if;

  select m.* into v_membership
  from public.business_memberships m
  join public.businesses b on b.id = m.business_id and b.is_active = true
  join public.branches br on br.id = m.branch_id and br.business_id = m.business_id and br.is_active = true
  where m.id = p_membership_id
    and m.business_id = p_business_id
    and m.branch_id = p_branch_id
    and m.is_active = true
    and m.removed_at is null
    and m.role in ('ADMIN', 'CASHIER');
  if not found then
    raise exception 'ACTIVE_MEMBERSHIP_REQUIRED' using errcode = '42501';
  end if;

  insert into private.customer_directory (
    business_id, canonical_phone, customer_id, display_name,
    created_at, updated_at, created_by_membership_id, updated_by_membership_id
  ) values (
    p_business_id, p_canonical_phone, p_customer_id, btrim(p_display_name),
    coalesce(p_created_at, now()), now(), p_membership_id, p_membership_id
  )
  on conflict (business_id, canonical_phone) do nothing;

  get diagnostics v_inserted = row_count;
  select d.* into v_row
  from private.customer_directory d
  where d.business_id = p_business_id and d.canonical_phone = p_canonical_phone
  for update;

  -- Identity is phone-based within a business. Do not silently rename an existing
  -- customer or replace its canonical ID when another device has a different local ID.
  return query select v_row.customer_id, v_row.canonical_phone, v_row.display_name,
    v_row.created_at, v_row.updated_at, v_inserted;
end;
$function$;

create or replace function public.sync_customer_directory_list(
  p_business_id text,
  p_branch_id text,
  p_membership_id text,
  p_after_updated_at timestamptz default null,
  p_limit integer default 100
)
returns table (
  customer_id text,
  canonical_phone text,
  display_name text,
  created_at timestamptz,
  updated_at timestamptz
)
language plpgsql
security definer
set search_path to ''
as $function$
begin
  if p_business_id is null or p_branch_id is null or p_membership_id is null
     or p_limit is null or p_limit < 1 or p_limit > 500 then
    raise exception 'INVALID_CUSTOMER_SYNC_REQUEST' using errcode = '22023';
  end if;

  if not exists (
    select 1
    from public.business_memberships m
    join public.businesses b on b.id = m.business_id and b.is_active = true
    join public.branches br on br.id = m.branch_id and br.business_id = m.business_id and br.is_active = true
    where m.id = p_membership_id and m.business_id = p_business_id
      and m.branch_id = p_branch_id and m.is_active = true and m.removed_at is null
      and m.role in ('ADMIN', 'CASHIER')
  ) then
    raise exception 'ACTIVE_MEMBERSHIP_REQUIRED' using errcode = '42501';
  end if;

  return query
  select d.customer_id, d.canonical_phone, d.display_name, d.created_at, d.updated_at
  from private.customer_directory d
  where d.business_id = p_business_id
    and (p_after_updated_at is null or d.updated_at >= p_after_updated_at)
  order by d.updated_at asc, d.canonical_phone asc
  limit p_limit;
end;
$function$;

revoke all on function public.sync_customer_directory_upsert(text,text,text,text,text,text,timestamptz) from public, anon, authenticated;
revoke all on function public.sync_customer_directory_list(text,text,text,timestamptz,integer) from public, anon, authenticated;
grant execute on function public.sync_customer_directory_upsert(text,text,text,text,text,text,timestamptz) to service_role;
grant execute on function public.sync_customer_directory_list(text,text,text,timestamptz,integer) to service_role;

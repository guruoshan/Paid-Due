create schema if not exists private;

create table public.businesses (
  id text primary key,
  is_active boolean not null default true
);
create table public.branches (
  id text primary key,
  business_id text not null references public.businesses(id),
  is_active boolean not null default true
);
create table public.business_memberships (
  id text primary key,
  business_id text not null references public.businesses(id),
  branch_id text not null references public.branches(id),
  role text not null check (role in ('ADMIN','CASHIER')),
  is_active boolean not null default true,
  removed_at timestamptz null
);

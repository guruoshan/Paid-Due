create schema if not exists private;
create schema if not exists auth;

create table if not exists auth.users (
  id uuid primary key
);
create or replace function auth.uid()
returns uuid
language sql stable
as $
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$;

create table public.profiles (
  auth_user_id uuid primary key references auth.users(id),
  is_active boolean not null default true,
  display_name text
);

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
  auth_user_id uuid references auth.users(id),
  staff_identity_id text,
  is_active boolean not null default true,
  removed_at timestamptz null
);

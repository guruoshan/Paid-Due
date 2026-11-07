-- Minimal disposable support schema for the removed-active authorization contract.
-- This file is used only by the SQL Behavioral Gate local Supabase stack.
ALTER TABLE public.businesses ADD COLUMN IF NOT EXISTS name text;
UPDATE public.businesses SET name = COALESCE(name, id);
ALTER TABLE public.businesses ALTER COLUMN name SET DEFAULT 'Test Business';

ALTER TABLE public.business_memberships ADD COLUMN IF NOT EXISTS local_user_id text;
ALTER TABLE public.business_memberships ADD COLUMN IF NOT EXISTS can_issue_invoice boolean NOT NULL DEFAULT false;
ALTER TABLE public.business_memberships ADD COLUMN IF NOT EXISTS can_issue_receipt boolean NOT NULL DEFAULT false;
ALTER TABLE public.business_memberships ADD COLUMN IF NOT EXISTS can_view_broad_history boolean NOT NULL DEFAULT false;
ALTER TABLE public.business_memberships ADD COLUMN IF NOT EXISTS can_manage_products boolean NOT NULL DEFAULT false;
ALTER TABLE public.business_memberships ADD COLUMN IF NOT EXISTS can_view_reports boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS public.profiles (
  auth_user_id uuid PRIMARY KEY REFERENCES auth.users(id),
  is_active boolean NOT NULL DEFAULT true,
  display_name text,
  canonical_phone text
);
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

CREATE OR REPLACE FUNCTION private.is_business_member(requested_business_id text)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO ''
AS $function$
  SELECT EXISTS (
    SELECT 1 FROM public.business_memberships bm
    JOIN public.businesses b ON b.id = bm.business_id AND b.is_active = true
    JOIN public.branches br ON br.id = bm.branch_id AND br.business_id = bm.business_id AND br.is_active = true
    WHERE bm.business_id = requested_business_id AND bm.auth_user_id = (SELECT auth.uid())
      AND bm.is_active = true AND bm.removed_at IS NULL AND bm.role = 'ADMIN'
  );
$function$;

CREATE POLICY profiles_self_or_shared_business_select ON public.profiles
FOR SELECT TO authenticated
USING (
  auth_user_id = (SELECT auth.uid())
  OR EXISTS (
    SELECT 1 FROM public.business_memberships target_membership
    WHERE target_membership.auth_user_id = profiles.auth_user_id
      AND target_membership.is_active = true
      AND (SELECT private.is_business_member(target_membership.business_id))
  )
);
GRANT SELECT ON public.profiles TO authenticated;
GRANT USAGE ON SCHEMA private TO authenticated;
GRANT EXECUTE ON FUNCTION private.is_business_member(text) TO authenticated;

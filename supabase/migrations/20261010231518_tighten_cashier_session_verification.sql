create or replace function public.verify_cashier_sync_session(p_token_hash text)
returns table(membership_id text, business_id text, branch_id text)
language sql security definer set search_path to '' as $$
  select m.id, m.business_id, m.branch_id
  from private.cashier_sync_sessions s
  join public.business_memberships m on m.id = s.membership_id
  join private.staff_identities i on i.id = m.staff_identity_id
  join public.businesses b on b.id = m.business_id and b.is_active
  join public.branches br on br.id = m.branch_id
    and br.business_id = m.business_id and br.is_active
  where s.token_hash = p_token_hash
    and s.expires_at > now()
    and s.revoked_at is null
    and m.is_active
    and m.removed_at is null
    and i.is_active
    and m.role = 'CASHIER'
  limit 1;
$$;

---
name: supabase-security-architect
description: Supabase Row Level Security (RLS), Postgres security policies, auth JWT claims, role-based access control (RBAC), and API authorization boundaries. Use when designing backend schemas, auditing Supabase RLS policies, securing API endpoints, or managing auth roles.
---

# Supabase Security Architect

## Activation Criteria
- Writing, auditing, or modifying Supabase schema, SQL migrations, or RLS policies.
- Configuring authentication flows, JWT token handling, or Role-Based Access Control (RBAC).
- Reviewing backend security boundaries between Android clients and Supabase services.

## Engineering Standards
- **Strict Row Level Security**: Enable RLS on every table (`ALTER TABLE ... ENABLE ROW LEVEL SECURITY`). Deny all access by default and explicitly grant scoped permissions.
- **Tenant & User Isolation**: Restrict SELECT, INSERT, UPDATE, and DELETE operations to authenticated users matching `auth.uid()` or validated organization claims.
- **Service Role Protection**: Never expose the Supabase `service_role` key inside the Android application. Use anonymous or authenticated client keys only.
- **RPC & Definer Functions**: Ensure PostgreSQL RPC functions specified as `SECURITY DEFINER` carefully validate caller identity and sanitize input parameters.
- **Migration Safety**: Never run destructive table resets (`DROP TABLE` / `TRUNCATE`) without explicit governance authorization.

## Verification Steps
1. Test RLS policies: verify unauthorized JWT tokens receive empty results or RLS policy violations.
2. Confirm client binary contains zero embedded service-role keys or admin credentials.
3. Validate SQL migration scripts against Supabase CLI or staging environments.

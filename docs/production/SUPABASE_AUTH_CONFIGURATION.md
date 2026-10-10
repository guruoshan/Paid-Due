# Supabase Auth configuration reconciliation

Status: BLOCKED — hosted Auth settings are not fully captured by repository evidence.

Project reference: `ttvdpgxqvvtjnxxfkoui`

## What is verified in Git

`supabase/config.toml` records the project reference and Edge Function gateway JWT settings only:

- `api.verify_jwt = false`
- `staff-invite.verify_jwt = true`
- `staff-activate.verify_jwt = false`
- `staff-open.verify_jwt = false`
- `staff-preview.verify_jwt = false`

These are Edge Function gateway settings. They are not a complete representation of the hosted Supabase Auth configuration.

## What is not verified

The connected Supabase project metadata available during reconciliation did not expose the full hosted Auth settings. The following values therefore remain unverified and must not be guessed:

- enabled Auth providers and provider-specific configuration
- signup enablement and email confirmation behavior
- site URL and additional redirect URLs
- email/phone OTP and password policy settings
- session/refresh-token settings
- rate limits and CAPTCHA settings, where configured
- SMTP/email template configuration, where configured

No secret values, keys, tokens, or credentials belong in this document or in Git.

## Required reconciliation procedure

1. Open the Supabase Dashboard for project `ttvdpgxqvvtjnxxfkoui`.
2. Capture the actual values from **Authentication → URL Configuration**, **Authentication → Sign In / Providers**, and any other Auth settings pages relevant to enabled providers and signup/session behavior.
3. If the project owner has authorized access to the Supabase Management API, retrieve the project's Auth configuration using the current official Management API endpoint and an access token held only in the local environment. Never paste the token into a prompt, commit it, or include it in command output. Cross-check API output against the dashboard.
4. Populate a sanitized table below with each setting's actual production value, whether it is represented in `supabase/config.toml`, and the supported mechanism used to reproduce it.
5. Put settings supported by Supabase CLI configuration into `supabase/config.toml`. For settings not represented by the CLI config, document the exact dashboard/API setup steps and a post-setup verification step. Do not add unsupported keys to `config.toml`.
6. Review the diff and run the repository's supported Supabase configuration validation before merging. Do not push settings to production as part of this documentation task.

## Verified-settings register

| Setting group | Production value | Source evidence | Reproducible from Git? |
|---|---|---|---|
| Project reference | `ttvdpgxqvvtjnxxfkoui` | `supabase/config.toml` and connected project metadata | Yes |
| Edge Function gateway JWT flags | Values listed above | `supabase/config.toml` | Yes |
| Hosted Auth providers | NOT YET RETRIEVED | Required: Dashboard or authorized Management API response | Unknown |
| Signup / confirmation behavior | NOT YET RETRIEVED | Required: Dashboard or authorized Management API response | Unknown |
| Site URL / redirect allow-list | NOT YET RETRIEVED | Required: Dashboard or authorized Management API response | Unknown |
| Password / OTP / session / rate-limit settings | NOT YET RETRIEVED | Required: Dashboard or authorized Management API response | Unknown |
| Email delivery / templates, if used | NOT YET RETRIEVED | Required: Dashboard or authorized Management API response | Unknown |

## Certification rule

Do not certify that Git can recreate production Auth until every applicable row above has an evidence-backed production value and a documented reproduction method. If a setting is intentionally dashboard-managed, mark it **INTENTIONALLY DIFFERENT / DASHBOARD-MANAGED**, document the operator procedure, and verify it after setup. This file is a transparent blocker record, not proof that Auth has been reconciled.

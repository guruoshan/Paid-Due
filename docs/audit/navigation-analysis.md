# Navigation and entry-point analysis

**Pinned source:** `f4c78e6c052720e270b88e42ce940335e7715069`  
**Status:** PARTIAL — primary Compose NavHost and activity deep-link handling inspected.

## Primary application entry point

`MainActivity.kt` is an `@AndroidEntryPoint` activity. It:
- calls `supabase.handleDeeplinks(intent)` during `onCreate`;
- starts `financialSyncScheduler.startAutoSync()`;
- renders `DmrApp`;
- calls `handleDeeplinks(intent)` again from `onNewIntent`.

Therefore auth deep-link handling is a runtime entry path outside the Compose route graph and must not be treated as dead code based only on route search.

## Compose graph

`DmrApp.kt` creates a `NavHost(navController, startDestination)`. The fetched source includes these route destinations:

| Route / destination | Observed behavior |
|---|---|
| LANGUAGE | Language selection, then welcome |
| WELCOME | Login and sign-up entry |
| SIGNUP_ROLE | Admin signup/setup routing |
| SETUP | Setup ViewModel and setup screen |
| LOGIN | Login ViewModel; only ADMIN proceeds to ADMIN_HUB |
| ADMIN_HUB | ADMIN-only hub with register/customers/reports/settings/notifications navigation |
| REGISTER | ADMIN-only register screen |
| NOTIFICATIONS | Notifications screen |
| NOTIFICATION_PERMISSION_REQUEST | Parameterized permission request route |
| REPORTS | Reporting screen and customer export |
| SETTINGS | Settings, customer navigation, help and logout |
| HELP_FAQ | Help/FAQ screen |
| FINANCIAL_METRICS_FAQ | Financial metrics explanation |
| CUSTOMERS | Customer list and export |
| CUSTOMER_DETAIL | Parameterized customer detail/payment/invoice screen |

The file has 15 `composable(` call sites in the fetched text.

## Startup reachability and authorization gates

Before the active navigation graph is rendered, startup recovery runs. The code shown performs financial startup recovery, cloud tenant bootstrap recovery, trusted session restoration and setup/language selection. The app does not create the NavHost while startup recovery is still running or when recovery fails.

The restored session path only sends an ADMIN session to ADMIN_HUB. A restored non-ADMIN session is cleared. Login success similarly sends only ADMIN to ADMIN_HUB; non-ADMIN sessions are cleared and sent to welcome. ADMIN_HUB and REGISTER independently guard their content.

## Deep links and previews

- Supabase auth deep links are handled at the Activity layer in both creation and new-intent paths.
- A complete review of AndroidManifest intent filters and Supabase deep-link URI configuration was not completed.
- `@Preview` was not exhaustively searched in all 242 production files; no repository-wide absence conclusion is made.
- Nested graphs, route constants, generated routes and every `navigate()` call were not fully enumerated.

## Retired cashier/staff route conclusion

The inspected active NavHost does not declare a cashier-management or staff-invite route. This does **not** establish that all historical backend, migration, notification, deep-link or stored-data references are absent. No cashier-related code is recommended for deletion by this navigation review.

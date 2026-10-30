# Dependency and dynamic-reference analysis

**Pinned source:** `f4c78e6c052720e270b88e42ce940335e7715069`  
**Status:** PARTIAL — selected entry points and framework boundaries were traced; complete graph analysis remains outstanding.

## Hilt / dependency injection evidence

### MainActivity

`app/src/main/java/com/dailymoneyregister/app/MainActivity.kt` (103 lines) is annotated `@AndroidEntryPoint`. It injects:

- `SupabaseClient`
- `FinancialSyncScheduler`
- `SetupRepository`
- `LanguageManager`
- `DebugSessionBootstrapper`
- `StartupRecoveryCoordinator`
- `TrustedSessionStore`
- `TrustedSessionRestorer`

The activity starts automatic financial sync and passes startup/session dependencies to `DmrApp`.

### DmrApp

`app/src/main/java/com/dailymoneyregister/app/ui/DmrApp.kt` is a Compose entry point. The inspected navigation graph obtains screen ViewModels with `hiltViewModel()`, including setup, login, register, reporting, customer list/detail and settings ViewModels. This is a dynamic/generated DI path and means a declaration cannot be called unused solely because there is no ordinary constructor call.

### Not completed

- Full inventory of `@Inject`, `@Provides`, `@Binds`, component scopes and Hilt modules.
- Complete dependency graph from every injection point to providers.
- `@EntryPoint`/entry-point access inventory.
- Generated Hilt graph inspection and unused binding detection.

## Compose / navigation

The inspected `DmrApp.kt` contains a `NavHost` with routes for language, welcome, sign-up role, setup, login, admin hub, register, notifications, notification permission request, reports, settings, help/FAQ, financial metrics FAQ, customers and customer detail. It contains 15 `composable(` call sites in the fetched file.

Role gates are visible in the active UI: restored non-ADMIN sessions are cleared; login routes non-ADMIN sessions back to welcome; ADMIN_HUB and REGISTER guard their content with ADMIN checks.

`MainActivity.kt` calls Supabase `handleDeeplinks(intent)` in both `onCreate` and `onNewIntent`. This is an external auth deep-link path and must be considered in reachability analysis. No `@Preview` match was found in the selected entry-point files; this is not a repository-wide absence claim.

Not completed: route constant reference closure, manifest intent filters, all deep-link destinations, nested navigation, all previews and all composable call sites.

## Room

`DmrDatabase.kt` is 898 lines and has 20 `fun` declaration-token matches in the fetched text; the source contains extensive migration code (106 occurrences of the word `Migration` in the fetched text). It is a critical dynamic/annotation-driven boundary. The full `@Entity` / `@Dao` / database abstract-member / migration-registration graph was not enumerated. No schema or migration files were changed.

## Serialization / reflection

`RemoteEnvelopeMaterializer.kt` contains 308 occurrences of `Json` and 81 occurrences of `envelope` in a simple token count; this is an indication of JSON-centric materialization, not a full serialization inventory. No exhaustive Gson, kotlinx.serialization, adapter, serializer, reflection or persisted-string dispatch analysis was completed.

## Conclusion

No symbol in this report is proven unused. Hilt-generated bindings, Compose navigation, Room annotations, deep links and serialization are dynamic reference mechanisms that require compiler/manifest/schema-aware tracing before any deletion decision.

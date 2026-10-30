# Unused-code candidates

**Pinned source:** `f4c78e6c052720e270b88e42ce940335e7715069`  
**Status:** INCOMPLETE — no declaration is proven unreferenced in this pass.

## Classification summary

These counts apply only to individually inspected items, not the repository as a whole.

| Classification | Confirmed count | Meaning |
|---|---:|---|
| CONFIRMED_UNREFERENCED | 0 | No declaration proven unused after complete reference analysis |
| UNREFERENCED_DYNAMIC_RISK | 0 | No item classified; dynamic paths not fully indexed |
| RETIRED_FEATURE | 0 | No source family proven unreachable across all entry paths |
| REVIEW_REQUIRED | 0 | No individual candidate completed to this threshold |
| KEEP | 1 | Directly reachable item below |

## Individually assessed item

| Symbol | File | Classification | Evidence |
|---|---|---|---|
| `DebugSessionBootstrapper` / `bootstrapIfDebug()` | `core/security/DebugSessionBootstrapper.kt` | KEEP | `MainActivity` injects and passes it to `DmrApp`; `DmrApp` calls it only after setup is complete. The implementation checks `ApplicationInfo.FLAG_DEBUGGABLE` and returns false for non-debuggable builds. This is a runtime-guarded debug path, not an unused declaration. |

## Important dynamic-reference warnings

- Hilt: constructor injection, `@Provides`, `@Binds`, entry points and generated bindings.
- Compose: route registration, `hiltViewModel()`, composable callbacks and previews.
- Room: entity/DAO annotations, database abstract members, migration registration and schema references.
- Serialization: Gson/kotlinx.serialization adapters, generated serializers, reflection and persisted type names.
- Android: manifest declarations, intent filters, activity/service/receiver entry points and deep links.
- Financial sync: persisted operation type strings, replay/recovery dispatch, canonical payload and envelope fields.
- Tests: test-only references do not imply production reachability, but they are important evidence when evaluating intended contracts.

## Candidate policy

Do not delete a declaration unless:
1. its declaration and all source-set references are indexed at the pinned commit;
2. generated, annotation-driven, manifest and dynamic paths are checked;
3. persisted values and serialized names are checked where relevant;
4. tests that intentionally exercise the contract are understood;
5. a reviewer confirms the proposed deletion is safe.

Search-result absence alone is insufficient. No code deletion is recommended by this report.

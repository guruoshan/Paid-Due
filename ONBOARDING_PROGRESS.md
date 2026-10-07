# Onboarding Progress

- [x] P1 Launch+navigation (this run)
- [x] P2 Business Admin sign-up wiring
- [x] P3 Local invite model + admin invite UI
- [ ] P4 Deep link + employee activation
- [ ] P5 Tests + verify

P1 files created/changed:

- ONBOARDING_PROGRESS.md
- app/src/main/java/com/dailymoneyregister/app/ui/DmrApp.kt
- app/src/main/java/com/dailymoneyregister/core/i18n/LanguageManager.kt
- app/src/main/java/com/dailymoneyregister/core/ui/PasswordField.kt
- app/src/main/java/com/dailymoneyregister/feature/auth/ui/LoginScreen.kt
- app/src/main/java/com/dailymoneyregister/feature/onboarding/ui/LanguagePickerScreen.kt
- app/src/main/java/com/dailymoneyregister/feature/onboarding/ui/SignUpRoleScreen.kt
- app/src/main/java/com/dailymoneyregister/feature/onboarding/ui/WelcomeScreen.kt
- app/src/main/java/com/dailymoneyregister/feature/setup/ui/SetupScreen.kt
- app/src/main/res/values/strings_onboarding.xml

P2 files changed:

- ONBOARDING_PROGRESS.md
- app/src/main/java/com/dailymoneyregister/app/ui/DmrApp.kt
- app/src/main/java/com/dailymoneyregister/feature/onboarding/ui/SignUpRoleScreen.kt
- app/src/main/java/com/dailymoneyregister/feature/setup/ui/SetupScreen.kt
- app/src/main/java/com/dailymoneyregister/feature/setup/ui/SetupViewModel.kt
- app/src/main/res/values/strings_onboarding.xml

P3 files created/changed:

- ONBOARDING_PROGRESS.md
- app/src/main/java/com/dailymoneyregister/core/ui/PhoneNumberInput.kt
- app/src/main/java/com/dailymoneyregister/feature/admin/ui/CashierManagementScreen.kt
- app/src/main/java/com/dailymoneyregister/feature/admin/ui/CashierManagementViewModel.kt
- app/src/main/java/com/dailymoneyregister/feature/invite/data/InviteService.kt
- app/src/main/java/com/dailymoneyregister/feature/invite/data/InviteStore.kt
- app/src/main/java/com/dailymoneyregister/feature/invite/data/LocalInviteStore.kt
- app/src/main/java/com/dailymoneyregister/feature/invite/data/StaffInvite.kt
- app/src/main/java/com/dailymoneyregister/feature/invite/di/InviteModule.kt
- app/src/main/res/values/strings_onboarding.xml

NEXT: P4


P4 finished manually (not by Codex):
- feature/invite/data/InviteActivationRepository.kt (new)
- feature/invite/ui/InviteActivateViewModel.kt, InviteActivateScreen.kt (new)
- core/ui/PasswordField.kt (added optional keyboardOptions)
- res/values/strings_invite_activation.xml (new)
- app/MainActivity.kt (read invite link only on fresh start)
- createCashier NOT modified; activation uses it with the business Admin as actor, then clears mustChangePin.

NEXT: P5 (tests + manual verify)

P4b (manual): invite phone now supports any country (picker enabled, uses picked country);
invite link no longer depends on Muscatera. Paid&Due public invite URL is configuration-driven and remains blocked until a Paid&Due-owned HTTPS endpoint and shared cross-device invite authority are deployed.


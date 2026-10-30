# Code health audit — main

## 1. Scope, baseline, and method

- Repository: guruoshan/Paid-Due
- Audit ref: main
- Audited commit: bed476678684fa8b14f64138a7503b6c7781ea76
- Report branch: audit/code-health-main, created directly from that exact commit.
- Scope: app/src/main Kotlin and Java files; direct-reference checks scoped to app/src (main, test, and androidTest); route, DI, debug-build, and feature-flag review.
- Governance read before audit: AGENTS.md; PROJECT_GOVERNANCE.md sections 1, 2, 3A, 9, 12, and 14; RELEASE_CHECKLIST.md; and the repository boardlock-engineer skill.
- No application source, Gradle file, schema, resource, test, or migration was changed. No device, emulator, Supabase, build, or test command was run.

### Search method

1. Read the Git tree recursively at the audited SHA. GitHub returned an untruncated tree.
2. Fetched all 235 Kotlin/Java files under app/src/main at that exact SHA and counted their text lines. The inventory below is the full main-source Kotlin/Java inventory.
3. Counted source-set files: main 322 total files / 235 Kotlin-Java; test 89 / 89 Kotlin-Java; androidTest 52 / 51 Kotlin-Java.
4. Used exact-name GitHub code search scoped to app/src to check candidate references across main, test, and androidTest. Opened the relevant declarations and inspected the startup/navigation and DI entry points.
5. Checked Hilt @Provides/@Binds declarations and their consumers; checked @Composable occurrences and navigation declarations; checked the debug bootstrapper, inbound sync flag, cashier/admin route gating, and Supabase auth deep-link setup.
6. Search results are treated as leads, not proof of absence. A zero-result search is not treated as proof of non-use.

Line-count convention: counts are the number of text lines returned by splitting the fetched UTF-8 file content on newline boundaries; a final empty segment is counted if the file ends in a newline. This convention is consistent across this inventory.

## 2. Main-source Kotlin/Java line inventory

| File | Lines |
|---|---:|
| app/src/main/java/com/dailymoneyregister/app/DmrApplication.kt | 58 |
| app/src/main/java/com/dailymoneyregister/app/MainActivity.kt | 103 |
| app/src/main/java/com/dailymoneyregister/app/ui/DmrApp.kt | 543 |
| app/src/main/java/com/dailymoneyregister/app/ui/HubScreens.kt | 221 |
| app/src/main/java/com/dailymoneyregister/core/cloud/BusinessRestorerModule.kt | 18 |
| app/src/main/java/com/dailymoneyregister/core/cloud/RemoteAdminAuthRepository.kt | 161 |
| app/src/main/java/com/dailymoneyregister/core/cloud/RemoteAdminBootstrapRepository.kt | 170 |
| app/src/main/java/com/dailymoneyregister/core/cloud/RemoteBusinessRestorer.kt | 451 |
| app/src/main/java/com/dailymoneyregister/core/cloud/RemoteTenantBootstrapCoordinator.kt | 285 |
| app/src/main/java/com/dailymoneyregister/core/cloud/RemoteTenantBootstrapRecoveryViewModel.kt | 58 |
| app/src/main/java/com/dailymoneyregister/core/cloud/RemoteTenantBootstrapStore.kt | 162 |
| app/src/main/java/com/dailymoneyregister/core/cloud/SupabaseModule.kt | 51 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/CustomerDirectorySyncClient.kt | 182 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/CustomerIdentityReconciler.kt | 200 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/FinancialEnvelopeCodec.kt | 190 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/FinancialEnvelopeEvidenceLoader.kt | 264 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/FinancialSyncContract.kt | 79 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/FinancialSyncEngine.kt | 634 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/FinancialSyncOperationPolicy.kt | 38 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/FinancialSyncOutboxPolicy.kt | 81 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/FinancialSyncScheduler.kt | 118 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/FinancialSyncStatus.kt | 70 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/InboundApplicationGate.kt | 52 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/InboundCursorCodec.kt | 23 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/InboundEnvelopeApplier.kt | 42 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/InboundIncompleteBanner.kt | 43 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/RemoteEnvelopeEvidenceApplier.kt | 425 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/RemoteEnvelopeMaterializer.kt | 1243 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/RemoteFinancialSyncRepository.kt | 379 |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/RetryBackoffStore.kt | 20 |
| app/src/main/java/com/dailymoneyregister/core/command/CommandExecutor.kt | 369 |
| app/src/main/java/com/dailymoneyregister/core/command/CommandFingerprint.kt | 16 |
| app/src/main/java/com/dailymoneyregister/core/command/OperationCommand.kt | 50 |
| app/src/main/java/com/dailymoneyregister/core/command/OperationStateMachine.kt | 42 |
| app/src/main/java/com/dailymoneyregister/core/database/Converters.kt | 75 |
| app/src/main/java/com/dailymoneyregister/core/database/DmrDatabase.kt | 898 |
| app/src/main/java/com/dailymoneyregister/core/database/dao/BusinessBranchDao.kt | 53 |
| app/src/main/java/com/dailymoneyregister/core/database/dao/CashShiftDao.kt | 45 |
| app/src/main/java/com/dailymoneyregister/core/database/dao/CloudStaffIdentityDao.kt | 33 |
| app/src/main/java/com/dailymoneyregister/core/database/dao/CustomerDao.kt | 167 |
| app/src/main/java/com/dailymoneyregister/core/database/dao/DocumentMediaDao.kt | 131 |
| app/src/main/java/com/dailymoneyregister/core/database/dao/ExpenseDao.kt | 175 |
| app/src/main/java/com/dailymoneyregister/core/database/dao/FinancialSyncOutboxDao.kt | 230 |
| app/src/main/java/com/dailymoneyregister/core/database/dao/InboundEnvelopeAuditDao.kt | 36 |
| app/src/main/java/com/dailymoneyregister/core/database/dao/LedgerDao.kt | 188 |
| app/src/main/java/com/dailymoneyregister/core/database/dao/OperationDao.kt | 132 |
| app/src/main/java/com/dailymoneyregister/core/database/dao/ProductDao.kt | 28 |
| app/src/main/java/com/dailymoneyregister/core/database/dao/ReportingReadDao.kt | 266 |
| app/src/main/java/com/dailymoneyregister/core/database/dao/TransactionDao.kt | 227 |
| app/src/main/java/com/dailymoneyregister/core/database/dao/UserDao.kt | 127 |
| app/src/main/java/com/dailymoneyregister/core/database/entity/CashShiftEntities.kt | 90 |
| app/src/main/java/com/dailymoneyregister/core/database/entity/CloudStaffIdentityEntity.kt | 51 |
| app/src/main/java/com/dailymoneyregister/core/database/entity/CoreEntities.kt | 330 |
| app/src/main/java/com/dailymoneyregister/core/database/entity/CouponEntities.kt | 176 |
| app/src/main/java/com/dailymoneyregister/core/database/entity/CreditEntities.kt | 152 |
| app/src/main/java/com/dailymoneyregister/core/database/entity/CustomerIdAliasEntity.kt | 29 |
| app/src/main/java/com/dailymoneyregister/core/database/entity/DocumentMediaEntities.kt | 203 |
| app/src/main/java/com/dailymoneyregister/core/database/entity/ExpenseEntities.kt | 208 |
| app/src/main/java/com/dailymoneyregister/core/database/entity/FinancialSyncOutboxEntity.kt | 64 |
| app/src/main/java/com/dailymoneyregister/core/database/entity/InboundEnvelopeAuditEventEntity.kt | 48 |
| app/src/main/java/com/dailymoneyregister/core/database/entity/LifecycleEventEntities.kt | 203 |
| app/src/main/java/com/dailymoneyregister/core/database/entity/OperationEntity.kt | 93 |
| app/src/main/java/com/dailymoneyregister/core/database/entity/TransactionEntities.kt | 222 |
| app/src/main/java/com/dailymoneyregister/core/database/readmodel/TransactionFinancialRecord.kt | 60 |
| app/src/main/java/com/dailymoneyregister/core/database/security/DatabaseEncryptionMigrator.kt | 1111 |
| app/src/main/java/com/dailymoneyregister/core/database/security/FinancialImmutabilityCallback.kt | 290 |
| app/src/main/java/com/dailymoneyregister/core/device/DeviceIdentityProvider.kt | 51 |
| app/src/main/java/com/dailymoneyregister/core/di/CommandModule.kt | 21 |
| app/src/main/java/com/dailymoneyregister/core/di/DatabaseModule.kt | 67 |
| app/src/main/java/com/dailymoneyregister/core/di/SecurityModule.kt | 29 |
| app/src/main/java/com/dailymoneyregister/core/i18n/LanguageManager.kt | 67 |
| app/src/main/java/com/dailymoneyregister/core/identity/PhoneNumberNormalizer.kt | 22 |
| app/src/main/java/com/dailymoneyregister/core/model/currency/CurrencyConfig.kt | 111 |
| app/src/main/java/com/dailymoneyregister/core/model/enums/ModelEnums.kt | 141 |
| app/src/main/java/com/dailymoneyregister/core/model/financial/FinancialTransactionView.kt | 434 |
| app/src/main/java/com/dailymoneyregister/core/model/financial/RecentFinancialActivity.kt | 499 |
| app/src/main/java/com/dailymoneyregister/core/model/ids/TypedIds.kt | 117 |
| app/src/main/java/com/dailymoneyregister/core/model/money/MoneyMinorUnits.kt | 175 |
| app/src/main/java/com/dailymoneyregister/core/model/tax/TaxRateParser.kt | 115 |
| app/src/main/java/com/dailymoneyregister/core/model/tax/TaxRoundingPolicy.kt | 103 |
| app/src/main/java/com/dailymoneyregister/core/model/timezone/TimezoneRegistry.kt | 57 |
| app/src/main/java/com/dailymoneyregister/core/security/DatabasePassphraseManager.kt | 365 |
| app/src/main/java/com/dailymoneyregister/core/security/DebugSessionBootstrapper.kt | 54 |
| app/src/main/java/com/dailymoneyregister/core/security/PasswordHasher.kt | 547 |
| app/src/main/java/com/dailymoneyregister/core/security/PermissionChecker.kt | 68 |
| app/src/main/java/com/dailymoneyregister/core/security/PinThrottler.kt | 96 |
| app/src/main/java/com/dailymoneyregister/core/security/SessionManager.kt | 38 |
| app/src/main/java/com/dailymoneyregister/core/security/TrustedSessionRestorer.kt | 77 |
| app/src/main/java/com/dailymoneyregister/core/security/TrustedSessionStore.kt | 71 |
| app/src/main/java/com/dailymoneyregister/core/serialization/DurableEnvelopeCodec.kt | 34 |
| app/src/main/java/com/dailymoneyregister/core/startup/StartupRecoveryCoordinator.kt | 69 |
| app/src/main/java/com/dailymoneyregister/core/ui/LanguageLabels.kt | 27 |
| app/src/main/java/com/dailymoneyregister/core/ui/PasswordField.kt | 54 |
| app/src/main/java/com/dailymoneyregister/core/ui/PhoneNumberInput.kt | 116 |
| app/src/main/java/com/dailymoneyregister/core/ui/UiText.kt | 29 |
| app/src/main/java/com/dailymoneyregister/core/ui/theme/RegisterColors.kt | 32 |
| app/src/main/java/com/dailymoneyregister/core/update/ReleaseUpdateCard.kt | 113 |
| app/src/main/java/com/dailymoneyregister/core/update/ReleaseUpdater.kt | 182 |
| app/src/main/java/com/dailymoneyregister/feature/auth/data/AuthRepository.kt | 438 |
| app/src/main/java/com/dailymoneyregister/feature/auth/ui/LoginInputValidator.kt | 13 |
| app/src/main/java/com/dailymoneyregister/feature/auth/ui/LoginScreen.kt | 104 |
| app/src/main/java/com/dailymoneyregister/feature/auth/ui/LoginViewModel.kt | 136 |
| app/src/main/java/com/dailymoneyregister/feature/customers/data/CouponCreditRepaymentCommand.kt | 127 |
| app/src/main/java/com/dailymoneyregister/feature/customers/data/CouponCreditRepaymentPayloadCodec.kt | 215 |
| app/src/main/java/com/dailymoneyregister/feature/customers/data/CreditRepaymentCommand.kt | 116 |
| app/src/main/java/com/dailymoneyregister/feature/customers/data/CreditRepaymentFifoAllocator.kt | 116 |
| app/src/main/java/com/dailymoneyregister/feature/customers/data/CreditRepaymentPayloadCodec.kt | 149 |
| app/src/main/java/com/dailymoneyregister/feature/customers/data/CustomerIdentity.kt | 94 |
| app/src/main/java/com/dailymoneyregister/feature/customers/data/CustomerRepository.kt | 276 |
| app/src/main/java/com/dailymoneyregister/feature/customers/data/InvoiceLinkBackfillRepository.kt | 132 |
| app/src/main/java/com/dailymoneyregister/feature/customers/export/CustomerExcelExportViewModel.kt | 34 |
| app/src/main/java/com/dailymoneyregister/feature/customers/export/CustomerExcelExporter.kt | 756 |
| app/src/main/java/com/dailymoneyregister/feature/customers/ui/CustomerDetailScreen.kt | 1661 |
| app/src/main/java/com/dailymoneyregister/feature/customers/ui/CustomerFormatting.kt | 26 |
| app/src/main/java/com/dailymoneyregister/feature/customers/ui/CustomerWhatsAppMessageComposer.kt | 78 |
| app/src/main/java/com/dailymoneyregister/feature/customers/ui/CustomersScreen.kt | 301 |
| app/src/main/java/com/dailymoneyregister/feature/customers/ui/CustomersViewModel.kt | 846 |
| app/src/main/java/com/dailymoneyregister/feature/customers/ui/UniversalWhatsAppMessageComposer.kt | 405 |
| app/src/main/java/com/dailymoneyregister/feature/customers/ui/WhatsAppTransport.kt | 126 |
| app/src/main/java/com/dailymoneyregister/feature/invoice/data/GenerateInvoiceCommand.kt | 46 |
| app/src/main/java/com/dailymoneyregister/feature/invoice/data/InvoiceRepository.kt | 268 |
| app/src/main/java/com/dailymoneyregister/feature/invoice/domain/InvoiceSnapshot.kt | 155 |
| app/src/main/java/com/dailymoneyregister/feature/invoice/domain/InvoiceSnapshotCodec.kt | 124 |
| app/src/main/java/com/dailymoneyregister/feature/invoice/pdf/InvoicePdfRenderer.kt | 425 |
| app/src/main/java/com/dailymoneyregister/feature/invoice/pdf/InvoiceSharing.kt | 105 |
| app/src/main/java/com/dailymoneyregister/feature/invoice/ui/InvoiceDialogs.kt | 233 |
| app/src/main/java/com/dailymoneyregister/feature/invoice/ui/InvoiceViewModel.kt | 304 |
| app/src/main/java/com/dailymoneyregister/feature/notifications/data/NotificationsRepository.kt | 142 |
| app/src/main/java/com/dailymoneyregister/feature/notifications/ui/NotificationsScreen.kt | 227 |
| app/src/main/java/com/dailymoneyregister/feature/notifications/ui/NotificationsViewModel.kt | 124 |
| app/src/main/java/com/dailymoneyregister/feature/onboarding/ui/LanguagePickerScreen.kt | 101 |
| app/src/main/java/com/dailymoneyregister/feature/onboarding/ui/SignUpRoleScreen.kt | 78 |
| app/src/main/java/com/dailymoneyregister/feature/onboarding/ui/WelcomeScreen.kt | 47 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/AmountCorrectionCommand.kt | 432 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/AmountCorrectionFinancialIntegrity.kt | 262 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/CashSalePayloadCodec.kt | 698 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/CouponFinancialRepository.kt | 347 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/CouponSaleCommand.kt | 315 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/CouponWalletReadRepository.kt | 89 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/CreditInvoiceReadRepository.kt | 136 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/CreditReturnPlan.kt | 656 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/CreditSaleCommand.kt | 55 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/CreditSalePayloadCodec.kt | 692 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/ExpenseCommand.kt | 339 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/ExpenseFinancialRepository.kt | 508 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/ExpenseLifecycleCommand.kt | 78 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/ExpenseReadProjection.kt | 266 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/ExpenseReadRepository.kt | 101 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/ExpenseSupportingDocumentRepository.kt | 346 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/FinancialCashShiftResolver.kt | 130 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/FinancialMutationAuthorizer.kt | 140 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/HistoryReadModel.kt | 488 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/HistoryRepository.kt | 270 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/LocalDayBoundaryFlow.kt | 37 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/OnlineSaleCommand.kt | 50 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/OnlineSalePayloadCodec.kt | 183 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/PaymentMethodChangeCommand.kt | 348 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/PaymentMethodChangeFinancialIntegrity.kt | 336 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/ProductRepository.kt | 84 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/RecentFinancialActivityReadRepository.kt | 383 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/RefundCommand.kt | 784 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/RefundFinancialIntegrity.kt | 360 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterCustomerReadRepository.kt | 79 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterRepository.kt | 8757 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterShiftCommand.kt | 420 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterShiftModels.kt | 215 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/RegisterShiftReportReadRepository.kt | 898 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/SaleFinancialWriter.kt | 197 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/TransactionActionEligibilityResolver.kt | 158 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/TransactionDetailsReadModel.kt | 82 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/TransactionDetailsReadRepository.kt | 154 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/VoidCommand.kt | 228 |
| app/src/main/java/com/dailymoneyregister/feature/register/data/VoidFinancialIntegrity.kt | 391 |
| app/src/main/java/com/dailymoneyregister/feature/register/domain/CalculatorEngine.kt | 232 |
| app/src/main/java/com/dailymoneyregister/feature/register/domain/DraftState.kt | 66 |
| app/src/main/java/com/dailymoneyregister/feature/register/receipt/ReceiptReadModel.kt | 110 |
| app/src/main/java/com/dailymoneyregister/feature/register/receipt/ReceiptRenderer.kt | 234 |
| app/src/main/java/com/dailymoneyregister/feature/register/receipt/ReceiptSharing.kt | 210 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/CouponCompactDialogR7A.kt | 1898 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/DayClosingPresentation.kt | 98 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/HistoryScreen.kt | 731 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/HistoryViewModel.kt | 247 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterCustomerOverlayDialogs.kt | 639 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterDestinationHost.kt | 157 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterExpensePresentation.kt | 1338 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterFinancialResultGuard.kt | 14 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterFinancialSessionContext.kt | 31 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterMainContent.kt | 664 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterOverlayHost.kt | 1092 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterPresentationState.kt | 65 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterRecentSessionFlow.kt | 48 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterResponsiveSizing.kt | 184 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterScreen.kt | 93 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterSessionReset.kt | 15 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterViewModel.kt | 3502 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/RegisterViewModelDependencies.kt | 140 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/TransactionDetailsScreen.kt | 1849 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/TransactionDetailsUiLogic.kt | 76 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/components/CashSaleSuccessOverlay.kt | 64 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/components/CompactAmountDisplay.kt | 169 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/components/CompactDescriptionField.kt | 65 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/components/ItemizedBillDialogs.kt | 432 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/components/PaymentMethodBar.kt | 35 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/components/RecentTransactionsPanel.kt | 492 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/components/RegisterKeypad.kt | 89 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/components/RegisterSecondaryActionBar.kt | 90 |
| app/src/main/java/com/dailymoneyregister/feature/register/ui/components/TaxShippingDialogs.kt | 143 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/data/ReportingAccess.kt | 114 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/data/ReportingAggregator.kt | 288 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/data/ReportingFilterOptionsRepository.kt | 62 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/data/ReportingReadRepository.kt | 561 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/data/ReportingRowMappers.kt | 830 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/di/ReportingModule.kt | 22 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/domain/ReportingModels.kt | 716 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/domain/ReportingPeriod.kt | 37 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/domain/ReportingPeriodResolver.kt | 98 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/pdf/ReportingPdfActions.kt | 361 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/pdf/ReportingPdfRenderer.kt | 239 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/ui/ReportingDashboardSection.kt | 164 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/ui/ReportingDashboardViewModel.kt | 149 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/ui/ReportingScreen.kt | 892 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/ui/ReportingUiProjection.kt | 475 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/ui/ReportingUiState.kt | 47 |
| app/src/main/java/com/dailymoneyregister/feature/reporting/ui/ReportingViewModel.kt | 534 |
| app/src/main/java/com/dailymoneyregister/feature/settings/data/MerchantQrRepository.kt | 87 |
| app/src/main/java/com/dailymoneyregister/feature/settings/data/SettingsRepository.kt | 158 |
| app/src/main/java/com/dailymoneyregister/feature/settings/ui/BusinessDetailsDialog.kt | 121 |
| app/src/main/java/com/dailymoneyregister/feature/settings/ui/HelpFaqScreens.kt | 119 |
| app/src/main/java/com/dailymoneyregister/feature/settings/ui/MerchantQrImage.kt | 55 |
| app/src/main/java/com/dailymoneyregister/feature/settings/ui/SettingsScreen.kt | 511 |
| app/src/main/java/com/dailymoneyregister/feature/settings/ui/SettingsViewModel.kt | 197 |
| app/src/main/java/com/dailymoneyregister/feature/setup/data/SetupPayloadCodec.kt | 152 |
| app/src/main/java/com/dailymoneyregister/feature/setup/data/SetupRepository.kt | 339 |
| app/src/main/java/com/dailymoneyregister/feature/setup/ui/SetupScreen.kt | 577 |
| app/src/main/java/com/dailymoneyregister/feature/setup/ui/SetupViewModel.kt | 636 |

## 3. God files (>800 lines)

Function lengths below are source-line spans from the function declaration through the brace-balanced body. Expression-bodied functions and abstract declarations can be approximate; the largest entries were manually checked against the file structure. “Top-level declarations” counts top-level classes/objects/interfaces/type aliases/functions/properties; “functions” counts function declarations in the file.

| File | Lines | Top-level declarations | Functions | Largest functions (line range; lines) | Responsibilities observed in code |
|---|---:|---:|---:|---|---|
| core/cloud/sync/RemoteEnvelopeMaterializer.kt | 1243 | 2 | 5 | materializeInboundEnvelope 37–676 (640); materializeEnvelope 1026–1241 (216); normalizeJson 714–718 (5); hasActorIdentity 678–683 (6); materializeNonTransactionOperation 726–731 (6) | Validates and applies inbound remote envelopes; checks actor/business/branch identity; materializes financial and non-transaction operations into Room; canonicalizes JSON and persists operation evidence. |
| core/database/DmrDatabase.kt | 898 | 1 | 34 | Migration.migrate 145–279 (135); migrate 486–588 (103); migrate 749–802 (54); getInstance 829–874 (46); migrate 282–325 (44); migrate 672–715 (44); migrate 329–362 (34); migrate 618–650 (33); migrate 365–396 (32); next migration body 399–429 (31) | Declares Room database and DAO accessors; owns the schema migration chain, SQLCipher loading, passphrase acquisition, singleton database construction, and migration-time integrity checks. |
| core/database/security/DatabaseEncryptionMigrator.kt | 1111 | 2 | 24 | checkpointAndNormalizePlaintextDatabase 301–407 (107); migratePlaintextDatabase 196–294 (99); verifyEncryptedSnapshot 537–622 (86); createEncryptedExport 409–485 (77); prepareDatabase 62–127 (66); reconcileInterruptedMigration 129–194 (66); verifyEncryptedDatabaseBasic 624–673 (50); readPlaintextSnapshot 487–535 (49); canOpenEncryptedDatabase 705–743 (39); hasPlainSqliteHeader 1033–1069 (37) | Handles plaintext-to-SQLCipher migration, WAL/checkpoint normalization, encrypted exports, integrity verification, snapshots, recovery after interrupted migration, and preservation checks. |
| feature/customers/ui/CustomerDetailScreen.kt | 1661 | 5 | 4 | CustomerDetailScreen 87–1422 (1336); InvoiceHistoryRow 1426–1462 (37); messageParty 111–124 (14); CreditReceivePaymentDialog 1473–1478 (6) | Builds the customer detail experience: customer identity/contact presentation, invoice history, credit/outstanding balances, receive-payment UI, invoice actions, and WhatsApp/share actions. |
| feature/customers/ui/CustomersViewModel.kt | 846 | 6 | 18 | confirmReceivePayment 501–783 (283); load 314–403 (90); launchShare 808–843 (36); reload 145–176 (32); confirmLinkOlderInvoices 197–226 (30); onRepaymentAmountChange 445–474 (30); selectRepaymentMethod 476–499 (24); openReceivePayment 407–427 (21); closeReceivePayment 429–443 (15); selectFilter 136–143 (8) | Coordinates customer list loading/filtering and customer detail state, invoice linking, repayment validation/submission, and invoice/customer sharing. |
| feature/register/data/RegisterRepository.kt | 8757 | 7 | 112 | executeAdmittedRefund 5171–6187 (1017); resolveRefundReplay 6195–7110 (916); executeCashSale 1306–1562 (257); resolveAmountCorrectionReplay 3972–4198 (227); executeAdmittedAmountCorrection 3759–3970 (212); recoverPendingCashSales 8369–8525 (157); executeAdmittedVoid 7926–8068 (143); resolveVoidReplay 8074–8213 (140); loadCreditRepaymentEvidence 2656–2792 (137); recoverPendingAmountCorrections 4200–4334 (135) | Central financial command/repository coordinator for cash/online/credit sales, coupons, repayments, expenses, refunds, voids, amount/payment corrections, cash-shift operations, replay validation, recovery, and durable command execution. |
| feature/register/data/RegisterShiftReportReadRepository.kt | 898 | 6 | 6 | getCurrentRegisterShiftReport 107–896 (790); registerShiftExternalMovement 15–32 (18); loadDayClosingBatches 142–159 (18); registerShiftExpectedCashMinor 39–44 (6); readRegisterShift 96–101 (6); registerShiftIsCouponTopUpIntent 34–38 (5) | Reads and reconciles cash-shift/day-close reporting from shift, sale, expense, coupon, and settlement evidence; derives expected cash and external movement context for a shift. |
| feature/register/ui/CouponCompactDialogR7A.kt | 1898 | 3 | 2 | CouponCompactDialogR7A 62–67 (6-line declaration; nested Compose content occupies the remainder); couponWhatsAppDateTime 1893–1898 (6) | Composes the compact coupon purchase/top-up interaction, customer selection/details, funding/payment inputs, validation states, and related presentation. Most of the file’s size is nested Compose layout/lambda content rather than separate named functions. |
| feature/register/ui/RegisterExpensePresentation.kt | 1338 | 9 | 8 | registerExpenseCategoryLabel20 1280–1316 (37); ExpenseDetailLine 575–601 (27); registerExpensePaymentLabel20 1319–1337 (19); expenseEvidenceMimeType 564–572 (9); expenseDetailsDateTime 43–48 (6); expenseDetailsLabel 59–64 (6); ExpenseDetailsScreen 75–80 (6-line declaration); ExpenseDialog 621–626 (6-line declaration) | Presents expense details and expense-entry dialogs, evidence/document preview and sharing, category/payment labels, and expense detail fields. |
| feature/register/ui/RegisterOverlayHost.kt | 1092 | 1 | 2 | RegisterOverlayHost 32–1090 (large conditional overlay composition; the declaration spans only 6 lines); local money formatter 473–478 (6) | Selects and renders register overlays/dialogs for coupon, customer, expense, transaction, correction, refund, and other register interaction states. |
| feature/register/ui/RegisterViewModel.kt | 3502 | 5 | 84 | confirmDayClosing 3178–3477 (300); confirmRefundToCoupon 1049–1299 (251); executeAmountCorrectionFromDetails 766–974 (209); confirmCreditSale 2278–2480 (203); executeRefundFromDetails 1301–1423 (123); lookupExpensePayeeByPhone 2787–2873 (87); openExpenseDetails 451–535 (85); refreshTodayExpenses 2684–2767 (84); confirmCouponSale 2481–2561 (81); confirmCouponTopUp 2563–2642 (80) | Coordinates register UI state and user actions across sales, credit, coupons, refunds, corrections, expenses, day closing, cash shifts, and reporting-facing state. |
| feature/register/ui/TransactionDetailsScreen.kt | 1849 | 22 | 21 | SettlementTimelineCard 1318–1400 (83); paymentMethodLabel 1684–1764 (81); TransactionItemCard 1542–1620 (79); lifecycleLabel 1623–1681 (59); settlementPurposeLabel 1767–1812 (46); PaymentMethodChangeTimelineCard 1218–1260 (43); AmountCorrectionTimelineCard 1174–1215 (42); OriginalTransactionTimelineCard 1104–1141 (38); RefundTimelineCard 1263–1293 (31); CreditRepaymentTimelineCard 1143–1172 (30) | Renders transaction lifetime details: original transaction, items, settlements, credit repayment, refunds, voids, amount corrections, payment-method changes, and lifecycle status. |
| feature/reporting/data/ReportingRowMappers.kt | 830 | 3 | 16 | creditBalancesAroundRepayment 120–154 (35); mapCorrection 428–462 (35); couponBalanceAfterEvent 157–175 (19); person 74–86 (13); requirePerson 89–100 (12); branch 61–66 (6); cashier 67–72 (6); mapSale 191–196 (6); mapRefund 299–304 (6); mapVoid 374–379 (6) | Maps immutable financial facts and lifecycle events into report rows; resolves customer/branch/cashier labels and computes credit/coupon balance context for reporting projections. |
| feature/reporting/ui/ReportingScreen.kt | 892 | 11 | 11 | ReportingRowCard 683–835 (153); ReportingScopeSelectors 361–425 (65); ReportingSummarySection 577–634 (58); ReportingPaymentFilters 519–574 (56); activityFilterLabel 463–516 (54); ReportingErrorState 838–891 (54); summaryMetricLabel 637–680 (44); ReportingActivityFilters 428–460 (33); ReportingPeriodSelector 297–327 (31); periodLabel 330–358 (29) | Composes report scope/period/payment/activity filters, summary metrics, report rows, customer navigation, loading/error states, and reporting interactions. |

## 4. Unused-code candidates

### Candidate table

| File | Line | Name | Classification | Search and references | Dynamic-path assessment |
|---|---:|---|---|---|---|
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/FinancialSyncOperationPolicy.kt | 37 | canUpload(role, type) | REVIEW_REQUIRED | Exact code search for canUpload scoped to app/src returned one file and one occurrence: this declaration. No direct caller was found in main, test, or androidTest. The sibling initialState function is used independently; it does not call canUpload. | No reflection, serialization name, Compose preview, or DI registration was identified for this function. A reflective invocation cannot be excluded by static search. It is financial sync policy, so governance classification is REVIEW_REQUIRED regardless of references. |

### Checks that did not produce a removal candidate

- Hilt registrations: production @Provides/@Binds declarations were enumerated in CommandModule, DatabaseModule, SecurityModule, SupabaseModule, ReportingModule, BusinessRestorerModule, and InboundEnvelopeApplierModule. The inspected providers/bindings have consumers: CommandExecutor is injected by financial/setup repositories; DAOs and DmrDatabase are injected by repositories; PinThrottler and LanguageManager are injected by AuthRepository and UI/startup components; SupabaseClient is injected by MainActivity and cloud repositories; ReportingReadRepository is injected by reporting ViewModels; BusinessRestorer is injected by AuthRepository; InboundEnvelopeApplier is injected by FinancialSyncEngine. TestDatabaseModule is androidTest-only. No DI-only unreferenced implementation was established.
- Composables: 41 files in app/src contain @Composable according to code search. No @Preview declaration was found. A complete per-composable call-site graph was not established, so no Composable is classified as confirmed unreferenced.
- Constants, enum entries, and sealed subtypes: not exhaustively enumerated. Room converters, enum-name persistence, JSON codecs, and historical event decoding create non-obvious string/reflection paths. No deletion candidate is asserted from a search miss.
- Files containing only unreferenced declarations: not exhaustively established. No file is labelled as such.
- Private declarations: the full set of private functions/properties/classes was not exhaustively enumerated and checked for same-file references. The candidate table is therefore not a complete private-declaration inventory.

## 5. Irrelevant-code and reachability review

| File / line | Reason examined | Reachability evidence | Classification |
|---|---|---|---|
| app/src/main/java/com/dailymoneyregister/core/security/DebugSessionBootstrapper.kt:15–54 | Debug-only session bootstrap code is present in production source. | MainActivity injects it and passes it to DmrApp. DmrApp calls bootstrapIfDebug during startup only after setup is complete and no trusted Admin session was restored. The method checks ApplicationInfo.FLAG_DEBUGGABLE and returns false when the installed application is not debuggable. Therefore the bypass path is not reached in release builds. | REVIEW_REQUIRED |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/InboundApplicationGate.kt:20–51; app/build.gradle.kts inbound flag configuration | Financial-sync feature flag and operation allowlist. | FinancialSyncEngine uses the gate. PAID_DUE_APPLY_INBOUND_FINANCIAL_ENVELOPES may be supplied as a Gradle property or environment variable; absent an override, release defaults to true. Release materialization is still limited by the explicit releaseSafeOperationTypes allowlist. This is not an always-off flag; it is on by default in release and can be overridden. | REVIEW_REQUIRED |
| app/src/main/java/com/dailymoneyregister/core/cloud/sync/FinancialSyncOperationPolicy.kt:37 | Public sync helper has no direct call site in app/src. | The declaration is the only exact app/src code-search hit. FinancialSyncOperationPolicy and adjacent upload policy are part of the active sync subsystem; no removal conclusion is made. | REVIEW_REQUIRED |
| app/src/main/java/com/dailymoneyregister/core/cloud/SupabaseModule.kt:39–41; app/src/main/java/com/dailymoneyregister/app/MainActivity.kt:18, 47, 83–86 | Auth deep-link configuration retains the host name invite, which could be confused with a retired staff-invite route. | Supabase Auth is configured with scheme paiddue and host invite. MainActivity calls Supabase handleDeeplinks on both onCreate and onNewIntent. This is a reachable auth deep-link handler. The audit did not establish that this host is a cashier/staff invitation flow; it may be used by Supabase Admin authentication links. | REVIEW_REQUIRED |

### Retired cashier / staff / invite flows

No current cashier-management, staff-management, or staff-invite screen/route was found in the DmrApp navigation graph. Current operational routes are Admin Hub, Register, Settings, Reports, Customers, Notifications, and customer detail; setup/login/onboarding routes are also present. Welcome navigates to SIGNUP_ROLE, which opens SignUpRoleScreen and permits the Admin setup path. It is not evidence of a cashier or staff invite screen.

The legacy cashier role still appears in model/history/security/sync code. ReportingRowMappers retains a cashier label mapping for historical actor attribution; the database retains migration/history handling; role gates reject non-Admin operational sessions. These are not reachable cashier-facing feature flows and may be necessary to decode or display historical evidence. They are not classified RETIRED_FEATURE or proposed for removal.

The Supabase Auth invite deep-link host is reachable as described above and is deliberately left unresolved rather than assumed to be retired.

### Commented-out blocks and TODO/FIXME/HACK

- Exact TODO/FIXME/HACK markers were searched in app/src/main. FIXME and HACK searches returned zero. The TODO search returned two indexed files, but inspection of the returned source excerpts did not verify a TODO marker in either file. No dated TODO/FIXME/HACK item is reported as confirmed.
- The Android versionName is generated from GITHUB_RUN_NUMBER at build time, so the checked-in Gradle file does not establish a unique current release tag against which to date comments.
- A complete parser-based scan for commented-out code blocks of 10 or more lines was not completed. No claim that such blocks are absent is made.

## 6. Classification summary

Counts below are for the four individually recorded review candidates above; they are not a count of every declaration in the repository.

| Classification | Count |
|---|---:|
| CONFIRMED_UNREFERENCED | 0 |
| UNREFERENCED_DYNAMIC_RISK | 0 |
| RETIRED_FEATURE | 0 |
| REVIEW_REQUIRED | 4 |
| KEEP | 0 |

No item is labelled safe to delete. REVIEW_REQUIRED items are the unreferenced financial-sync helper, the debug authentication bootstrapper, the active inbound financial-sync gate/flag, and the active auth deep-link configuration. Human review and approval under PROJECT_GOVERNANCE.md §14 are required before any removal.

## 7. Not checked / audit limits

- No Gradle build, lint, unit test, instrumentation test, emulator, device, or runtime inspection was run, by instruction.
- No Supabase query or deployment was run.
- Direct-reference search is constrained by GitHub code-search indexing and exact-name matching. It is not equivalent to a complete local ripgrep/AST scan of all source files.
- Private declarations, every public/internal symbol, every enum/sealed subtype, every Compose call site, and every file-only declaration were not exhaustively enumerated. The one unreferenced helper above is the only candidate verified by exact-name search; it is not proof that no other candidates exist.
- Kotlin/Compose call sites can be indirect through function references, navigation, lambdas, generated code, or reflection. Hilt generated wiring, Room reflection/annotations, Gson/JSON/serialization names, persisted enum strings, and dynamic when branches require dedicated analysis beyond plain text search.
- Commented-out code blocks were not exhaustively parsed. TODO age could not be tied to a specific release because versionName is environment-derived.
- God-file line counts and named function spans were obtained from fetched source. Brace-based function span detection can be approximate for expression bodies, nested lambdas, and abstract declarations; the file sizes and declaration totals are source-inventory measurements, not a complexity score.

## 8. Completeness statement

This is a bounded, evidence-based audit, **not a complete dead-code or reachability certification**. The full main-source Kotlin/Java inventory and all files above 800 lines are recorded, but exhaustive unused-declaration, commented-out-code, and dynamic-reference analysis remains incomplete because static text search cannot prove absence across Hilt generation, Room/Gson/serialization reflection, Compose/navigation indirection, and persisted-string dispatch. No removal should proceed from this report without the required human review.

# Financial certification result

- Commit SHA: c1de921e73b5f3be53131ae1ed78b3f2539e3e92
- Time (UTC): 2026-10-22T13:31:08Z
- overall_status: BLOCKED_OR_FAIL

## Operation results
### RECORD_SALE
- status: PASS
- failure reason: none recorded
- verify_outbox: PASS
- upload: PASS
- verify_digest: PASS
- download: PASS
- before: PASS
- after: PASS
- replay: PASS
- generate_reports: PASS

### RECORD_REFUND
- status: FAIL
- failure reason: Sender/receiver parity failed: operations[cert-record_refund-a3758374].result_reference_id: relationship/link differs sender="01a163cd-7874-706e-a0fc-dab082472782" receiver="cert-sale-transaction"; operations[cert-record_refund-a3758374].result_reference_type: authoritative value differs sender="REFUND_EVENT" receiver="TRANSACTION"
- verify_outbox: PASS
- upload: PASS
- verify_digest: PASS
- download: PASS
- before: PASS
- after: PASS
- replay: NOT_RUN — Stage did not execute
- generate_reports: NOT_RUN — Stage did not execute

### RECORD_VOID
- status: FAIL
- failure reason: Sender/receiver parity failed: operations[cert-record_void-b174cb83].result_reference_id: relationship/link differs sender="01a163cd-b734-76b1-9052-8f1051aead7a" receiver="cert-sale-transaction"; operations[cert-record_void-b174cb83].result_reference_type: authoritative value differs sender="VOID_EVENT" receiver="TRANSACTION"
- verify_outbox: PASS
- upload: PASS
- verify_digest: PASS
- download: PASS
- before: PASS
- after: PASS
- replay: NOT_RUN — Stage did not execute
- generate_reports: NOT_RUN — Stage did not execute

### RECORD_EXPENSE
- status: PASS
- failure reason: none recorded
- verify_outbox: PASS
- upload: PASS
- verify_digest: PASS
- download: PASS
- before: PASS
- after: PASS
- replay: PASS
- generate_reports: PASS

### RECORD_CREDIT_REPAYMENT
- status: BLOCKED
- failure reason: Missing dependency: isolated non-production Supabase project, protected test credentials, and real app-path CertificationExecutionEngine.Driver. Production project access is intentionally not accepted.
- verify_outbox: NOT_RUN — Stage did not execute
- upload: NOT_RUN — Stage did not execute
- verify_digest: NOT_RUN — Stage did not execute
- download: NOT_RUN — Stage did not execute
- before: NOT_RUN — Stage did not execute
- after: NOT_RUN — Stage did not execute
- replay: NOT_RUN — Stage did not execute
- generate_reports: NOT_RUN — Stage did not execute

### RECORD_COUPON_PURCHASE
- status: BLOCKED
- failure reason: Missing dependency: isolated non-production Supabase project, protected test credentials, and real app-path CertificationExecutionEngine.Driver. Production project access is intentionally not accepted.
- verify_outbox: NOT_RUN — Stage did not execute
- upload: NOT_RUN — Stage did not execute
- verify_digest: NOT_RUN — Stage did not execute
- download: NOT_RUN — Stage did not execute
- before: NOT_RUN — Stage did not execute
- after: NOT_RUN — Stage did not execute
- replay: NOT_RUN — Stage did not execute
- generate_reports: NOT_RUN — Stage did not execute

### RECORD_PAYMENT_METHOD_CHANGE
- status: FAIL
- failure reason: Sender/receiver parity failed: operations[cert-record_payment_method_change-a4066c6b].result_reference_id: relationship/link differs sender="01a163ce-2630-7a60-9d92-7ec38943411f" receiver="cert-sale-transaction"; operations[cert-record_payment_method_change-a4066c6b].result_reference_type: authoritative value differs sender="PAYMENT_METHOD_CHANGE_EVENT" receiver="TRANSACTION"
- verify_outbox: PASS
- upload: PASS
- verify_digest: PASS
- download: PASS
- before: PASS
- after: PASS
- replay: NOT_RUN — Stage did not execute
- generate_reports: NOT_RUN — Stage did not execute

### RECORD_AMOUNT_CORRECTION
- status: FAIL
- failure reason: Sender/receiver parity failed: operations[cert-record_amount_correction-2a7905b5].result_reference_id: relationship/link differs sender="01a163ce-5f66-766e-bfac-549023c8ffba" receiver="cert-sale-transaction"; operations[cert-record_amount_correction-2a7905b5].result_reference_type: authoritative value differs sender="AMOUNT_CORRECTION_EVENT" receiver="TRANSACTION"
- verify_outbox: PASS
- upload: PASS
- verify_digest: PASS
- download: PASS
- before: PASS
- after: PASS
- replay: NOT_RUN — Stage did not execute
- generate_reports: NOT_RUN — Stage did not execute

### OPEN_CASH_SHIFT
- status: BLOCKED
- failure reason: Normal app sync did not acknowledge the expense: acknowledged=0; supabaseUserId=c114c802-f481-472d-a312-17116485a30a; supabaseSessionPresent=true; adminScopeAuthorized=true; activeUserId=c114c802-f481-472d-a312-17116485a30a; activeRole=ADMIN; activeMembershipId=cert-membership-c114c802f481; activeBusinessId=cert-business-c114c802f481; activeBranchId=cert-branch-c114c802f481; expectedUserId=c114c802-f481-472d-a312-17116485a30a; expectedMembershipId=cert-membership-c114c802f481; expectedBusinessId=cert-business-c114c802f481; expectedBranchId=cert-branch-c114c802f481; outboxAfterSyncStatus=WAITING_RETRY; outboxAfterSyncActor=c114c802-f481-472d-a312-17116485a30a; outboxAfterSyncBusiness=cert-business-c114c802f481; outboxAfterSyncBranch=cert-branch-c114c802f481; outboxAfterSyncAttempts=1
- verify_outbox: PASS
- upload: NOT_RUN — Stage did not execute
- verify_digest: NOT_RUN — Stage did not execute
- download: NOT_RUN — Stage did not execute
- before: NOT_RUN — Stage did not execute
- after: NOT_RUN — Stage did not execute
- replay: NOT_RUN — Stage did not execute
- generate_reports: NOT_RUN — Stage did not execute

### COUNT_CASH_SHIFT
- status: BLOCKED
- failure reason: Normal app sync did not acknowledge the expense: acknowledged=0; supabaseUserId=f1c2ee58-a1be-46f8-bb49-329a868fdb49; supabaseSessionPresent=true; adminScopeAuthorized=true; activeUserId=f1c2ee58-a1be-46f8-bb49-329a868fdb49; activeRole=ADMIN; activeMembershipId=cert-membership-f1c2ee58a1be; activeBusinessId=cert-business-f1c2ee58a1be; activeBranchId=cert-branch-f1c2ee58a1be; expectedUserId=f1c2ee58-a1be-46f8-bb49-329a868fdb49; expectedMembershipId=cert-membership-f1c2ee58a1be; expectedBusinessId=cert-business-f1c2ee58a1be; expectedBranchId=cert-branch-f1c2ee58a1be; outboxAfterSyncStatus=WAITING_RETRY; outboxAfterSyncActor=f1c2ee58-a1be-46f8-bb49-329a868fdb49; outboxAfterSyncBusiness=cert-business-f1c2ee58a1be; outboxAfterSyncBranch=cert-branch-f1c2ee58a1be; outboxAfterSyncAttempts=1
- verify_outbox: PASS
- upload: NOT_RUN — Stage did not execute
- verify_digest: NOT_RUN — Stage did not execute
- download: NOT_RUN — Stage did not execute
- before: NOT_RUN — Stage did not execute
- after: NOT_RUN — Stage did not execute
- replay: NOT_RUN — Stage did not execute
- generate_reports: NOT_RUN — Stage did not execute

### CLOSE_CASH_SHIFT
- status: BLOCKED
- failure reason: Normal app sync did not acknowledge the expense: acknowledged=0; supabaseUserId=46a4e786-50a9-4af0-bf6a-4d9e535475a7; supabaseSessionPresent=true; adminScopeAuthorized=true; activeUserId=46a4e786-50a9-4af0-bf6a-4d9e535475a7; activeRole=ADMIN; activeMembershipId=cert-membership-46a4e78650a9; activeBusinessId=cert-business-46a4e78650a9; activeBranchId=cert-branch-46a4e78650a9; expectedUserId=46a4e786-50a9-4af0-bf6a-4d9e535475a7; expectedMembershipId=cert-membership-46a4e78650a9; expectedBusinessId=cert-business-46a4e78650a9; expectedBranchId=cert-branch-46a4e78650a9; outboxAfterSyncStatus=WAITING_RETRY; outboxAfterSyncActor=46a4e786-50a9-4af0-bf6a-4d9e535475a7; outboxAfterSyncBusiness=cert-business-46a4e78650a9; outboxAfterSyncBranch=cert-branch-46a4e78650a9; outboxAfterSyncAttempts=1
- verify_outbox: PASS
- upload: NOT_RUN — Stage did not execute
- verify_digest: NOT_RUN — Stage did not execute
- download: NOT_RUN — Stage did not execute
- before: NOT_RUN — Stage did not execute
- after: NOT_RUN — Stage did not execute
- replay: NOT_RUN — Stage did not execute
- generate_reports: NOT_RUN — Stage did not execute

### RECORD_EXPENSE_CORRECTION
- status: BLOCKED
- failure reason: Missing dependency: isolated non-production Supabase project, protected test credentials, and real app-path CertificationExecutionEngine.Driver. Production project access is intentionally not accepted.
- verify_outbox: NOT_RUN — Stage did not execute
- upload: NOT_RUN — Stage did not execute
- verify_digest: NOT_RUN — Stage did not execute
- download: NOT_RUN — Stage did not execute
- before: NOT_RUN — Stage did not execute
- after: NOT_RUN — Stage did not execute
- replay: NOT_RUN — Stage did not execute
- generate_reports: NOT_RUN — Stage did not execute

### RECORD_EXPENSE_VOID
- status: BLOCKED
- failure reason: Missing dependency: isolated non-production Supabase project, protected test credentials, and real app-path CertificationExecutionEngine.Driver. Production project access is intentionally not accepted.
- verify_outbox: NOT_RUN — Stage did not execute
- upload: NOT_RUN — Stage did not execute
- verify_digest: NOT_RUN — Stage did not execute
- download: NOT_RUN — Stage did not execute
- before: NOT_RUN — Stage did not execute
- after: NOT_RUN — Stage did not execute
- replay: NOT_RUN — Stage did not execute
- generate_reports: NOT_RUN — Stage did not execute

## Last 40 lines of certification script log
```text
> Task :app:assembleDebugAndroidTest
> Task :app:createDebugAndroidTestApkListingFileRedirect
gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_4-1792675477853.json

BUILD SUCCESSFUL in 4m 50s
81 actionable tasks: 81 executed
Configuration cache entry stored.
Performing Streamed Install
Success
Performing Streamed Install
Success

com.dailymoneyregister.core.cloud.sync.TwoDatabaseFinancialCertificationTest:....

Time: 0.485

OK (4 tests)



com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest:..

Time: 118.948

OK (2 tests)


/sdcard/Android/data/com.dailymoneyregister.debug/files/financial-certification-evidence/: 131 files pulled, 0 skipped. 0.2 MB/s (109365 bytes in 0.443s)
::error::Financial certification did not PASS. Evidence collection alone cannot satisfy the release gate.
::error::RECORD_REFUND: FAIL
::error::RECORD_VOID: FAIL
::error::RECORD_CREDIT_REPAYMENT: BLOCKED
::error::RECORD_COUPON_PURCHASE: BLOCKED
::error::RECORD_PAYMENT_METHOD_CHANGE: FAIL
::error::RECORD_AMOUNT_CORRECTION: FAIL
::error::OPEN_CASH_SHIFT: BLOCKED
::error::COUNT_CASH_SHIFT: BLOCKED
::error::CLOSE_CASH_SHIFT: BLOCKED
::error::RECORD_EXPENSE_CORRECTION: BLOCKED
::error::RECORD_EXPENSE_VOID: BLOCKED
```

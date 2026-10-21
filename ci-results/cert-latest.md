# Latest financial certification result

- Commit SHA: f9bd97c1de1c41dcd97403ee5d7a61cf109decdb
- Time (UTC): 2026-10-21T12:09:41Z
- overall_status: BLOCKED_OR_FAIL

## Operation results
### RECORD_SALE
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

### RECORD_REFUND
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

### RECORD_VOID
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

### RECORD_EXPENSE
- status: FAIL
- failure reason: Sender/receiver parity failed: cash_shift_events[*].row_count: row counts differ sender=1 receiver=0; cash_shift_events[01a15e5d-ede5-75a9-b6b8-e88693c11bab:OPENED].id: primary ID missing on one side sender={"actor_user_id":"40091b56-9629-4e3f-b424-2e4a3640cd48","amount_minor":0,"id":"01a15e5d-ede5-75a9-b6b8-e88693c11bab:OPENED","occurred_at":1792584576475,"operation_id":"01a15e5d-ede5-75a9-b6b8-e88693c11bab","shift_id":"01a15e5d-ede5-7609-9bb3-cfad1dd2763b","type":"OPENED"} receiver=null; cash_shifts[*].row_count: row counts differ sender=1 receiver=0; cash_shifts[01a15e5d-ede5-7609-9bb3-cfad1dd2763b].id: primary ID missing on one side sender={"branch_id":"cert-branch","created_at":1792584576475,"id":"01a15e5d-ede5-7609-9bb3-cfad1dd2763b","user_id":"40091b56-9629-4e3f-b424-2e4a3640cd48"} receiver=null; operations[cert-expense-operation].cash_shift_id: relationship/link differs sender="01a15e5d-ede5-7609-9bb3-cfad1dd2763b" receiver=null
- verify_outbox: PASS
- upload: PASS
- verify_digest: PASS
- download: PASS
- before: PASS
- after: PASS
- replay: NOT_RUN — Stage did not execute
- generate_reports: NOT_RUN — Stage did not execute

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

### RECORD_AMOUNT_CORRECTION
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

### OPEN_CASH_SHIFT
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

### COUNT_CASH_SHIFT
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

### CLOSE_CASH_SHIFT
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
	at com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest.recordExpense_passes_through_real_driver(RecordExpenseCertificationTest.kt:23)


Time: 9.328
There was 1 failure:
1) recordExpense_passes_through_real_driver(com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest)
java.lang.AssertionError: RECORD_EXPENSE must pass every real execution stage; blocker=Sender/receiver parity failed: cash_shift_events[*].row_count: row counts differ sender=1 receiver=0; cash_shift_events[01a15e5d-ede5-75a9-b6b8-e88693c11bab:OPENED].id: primary ID missing on one side sender={"actor_user_id":"40091b56-9629-4e3f-b424-2e4a3640cd48","amount_minor":0,"id":"01a15e5d-ede5-75a9-b6b8-e88693c11bab:OPENED","occurred_at":1792584576475,"operation_id":"01a15e5d-ede5-75a9-b6b8-e88693c11bab","shift_id":"01a15e5d-ede5-7609-9bb3-cfad1dd2763b","type":"OPENED"} receiver=null; cash_shifts[*].row_count: row counts differ sender=1 receiver=0; cash_shifts[01a15e5d-ede5-7609-9bb3-cfad1dd2763b].id: primary ID missing on one side sender={"branch_id":"cert-branch","created_at":1792584576475,"id":"01a15e5d-ede5-7609-9bb3-cfad1dd2763b","user_id":"40091b56-9629-4e3f-b424-2e4a3640cd48"} receiver=null; operations[cert-expense-operation].cash_shift_id: relationship/link differs sender="01a15e5d-ede5-7609-9bb3-cfad1dd2763b" receiver=null expected:<PASS> but was:<FAIL>
	at org.junit.Assert.fail(Assert.java:89)
	at org.junit.Assert.failNotEquals(Assert.java:835)
	at org.junit.Assert.assertEquals(Assert.java:120)
	at com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest$recordExpense_passes_through_real_driver$1.invokeSuspend(RecordExpenseCertificationTest.kt:40)
	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:34)
	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
	at kotlinx.coroutines.EventLoopImplBase.processNextEvent(EventLoop.common.kt:263)
	at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:94)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source:1)
	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source:1)
	at com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest.recordExpense_passes_through_real_driver(RecordExpenseCertificationTest.kt:23)

FAILURES!!!
Tests run: 2,  Failures: 1


/sdcard/Android/data/com.dailymoneyregister.debug/files/financial-certification-evidence/: 131 files pulled, 0 skipped. 0.0 MB/s (27040 bytes in 0.710s)
::error::Financial certification did not PASS. Evidence collection alone cannot satisfy the release gate.
::error::RECORD_SALE: BLOCKED
::error::RECORD_REFUND: BLOCKED
::error::RECORD_VOID: BLOCKED
::error::RECORD_EXPENSE: FAIL
::error::RECORD_CREDIT_REPAYMENT: BLOCKED
::error::RECORD_COUPON_PURCHASE: BLOCKED
::error::RECORD_PAYMENT_METHOD_CHANGE: BLOCKED
::error::RECORD_AMOUNT_CORRECTION: BLOCKED
::error::OPEN_CASH_SHIFT: BLOCKED
::error::COUNT_CASH_SHIFT: BLOCKED
::error::CLOSE_CASH_SHIFT: BLOCKED
::error::RECORD_EXPENSE_CORRECTION: BLOCKED
::error::RECORD_EXPENSE_VOID: BLOCKED
```

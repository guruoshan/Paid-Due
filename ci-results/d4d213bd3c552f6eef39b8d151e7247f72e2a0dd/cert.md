# Financial certification result

- Commit SHA: d4d213bd3c552f6eef39b8d151e7247f72e2a0dd
- Time (UTC): 2026-10-23T03:52:31Z
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

### RECORD_VOID
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
- failure reason: Normal app sync did not acknowledge the expense: acknowledged=0; supabaseUserId=54bf68c7-a28e-43b4-bfb8-79a38e606ebc; supabaseSessionPresent=true; adminScopeAuthorized=true; activeUserId=54bf68c7-a28e-43b4-bfb8-79a38e606ebc; activeRole=ADMIN; activeMembershipId=cert-membership-54bf68c7a28e; activeBusinessId=cert-business-54bf68c7a28e; activeBranchId=cert-branch-54bf68c7a28e; expectedUserId=54bf68c7-a28e-43b4-bfb8-79a38e606ebc; expectedMembershipId=cert-membership-54bf68c7a28e; expectedBusinessId=cert-business-54bf68c7a28e; expectedBranchId=cert-branch-54bf68c7a28e; outboxAfterSyncStatus=WAITING_RETRY; outboxAfterSyncActor=54bf68c7-a28e-43b4-bfb8-79a38e606ebc; outboxAfterSyncBusiness=cert-business-54bf68c7a28e; outboxAfterSyncBranch=cert-branch-54bf68c7a28e; outboxAfterSyncAttempts=1; syncFailureLog=07 E PaidDueFinancialSync: 	at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:94)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source:1)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source:1)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest.recordExpense_and_recordSale_pass_through_real_driver_in_one_run(RecordExpenseCertificationTest.kt:23)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at java.lang.reflect.Method.invoke(Native Method)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:56)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner$1.evaluate(BlockJUnit4ClassRunner.java:100)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:366)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:103)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:63)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at androidx.test.ext.junit.runners.AndroidJUnit4.run(AndroidJUnit4.java:162)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:128)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:27)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:137)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:115)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:68)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:59)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at androidx.test.runner.AndroidJUnitRunner.onStart(AndroidJUnitRunner.java:463)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at android.app.Instrumentation$InstrumentationThread.run(Instrumentation.java:2597)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: Caused by: java.lang.IllegalArgumentException: operationId rejected by validId: value="cert-credit-repayment-8e606ebc", length=30, maxLength=128, expectedFormat=^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncContractKt.validId(FinancialSyncContract.kt:13)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncContractKt.access$validId(FinancialSyncContract.kt:1)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncIdentity.<init>(FinancialSyncContract.kt:31)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine.syncOutbound(FinancialSyncEngine.kt:267)
10-23 03:51:20.552  5089  5107 E PaidDueFinancialSync: 	... 43 more

- verify_outbox: PASS
- upload: NOT_RUN — Stage did not execute
- verify_digest: NOT_RUN — Stage did not execute
- download: NOT_RUN — Stage did not execute
- before: NOT_RUN — Stage did not execute
- after: NOT_RUN — Stage did not execute
- replay: NOT_RUN — Stage did not execute
- generate_reports: NOT_RUN — Stage did not execute

### RECORD_COUPON_PURCHASE
- status: BLOCKED
- failure reason: Normal app sync did not acknowledge the expense: acknowledged=0; supabaseUserId=10479d45-5aeb-43c6-b6ef-cb0f010112dc; supabaseSessionPresent=true; adminScopeAuthorized=true; activeUserId=10479d45-5aeb-43c6-b6ef-cb0f010112dc; activeRole=ADMIN; activeMembershipId=cert-membership-10479d455aeb; activeBusinessId=cert-business-10479d455aeb; activeBranchId=cert-branch-10479d455aeb; expectedUserId=10479d45-5aeb-43c6-b6ef-cb0f010112dc; expectedMembershipId=cert-membership-10479d455aeb; expectedBusinessId=cert-business-10479d455aeb; expectedBranchId=cert-branch-10479d455aeb; outboxAfterSyncStatus=WAITING_RETRY; outboxAfterSyncActor=10479d45-5aeb-43c6-b6ef-cb0f010112dc; outboxAfterSyncBusiness=cert-business-10479d455aeb; outboxAfterSyncBranch=cert-branch-10479d455aeb; outboxAfterSyncAttempts=1; syncFailureLog=107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:94)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source:1)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source:1)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest.recordExpense_and_recordSale_pass_through_real_driver_in_one_run(RecordExpenseCertificationTest.kt:23)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at java.lang.reflect.Method.invoke(Native Method)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:56)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner$1.evaluate(BlockJUnit4ClassRunner.java:100)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:366)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:103)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:63)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at androidx.test.ext.junit.runners.AndroidJUnit4.run(AndroidJUnit4.java:162)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:128)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:27)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:137)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:115)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:68)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:59)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at androidx.test.runner.AndroidJUnitRunner.onStart(AndroidJUnitRunner.java:463)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at android.app.Instrumentation$InstrumentationThread.run(Instrumentation.java:2597)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: Caused by: java.lang.IllegalArgumentException: operationId rejected by validId: value="cert-coupon-purchase-010112dc", length=29, maxLength=128, expectedFormat=^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncContractKt.validId(FinancialSyncContract.kt:13)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncContractKt.access$validId(FinancialSyncContract.kt:1)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncIdentity.<init>(FinancialSyncContract.kt:31)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine.syncOutbound(FinancialSyncEngine.kt:267)
10-23 03:51:30.304  5089  5107 E PaidDueFinancialSync: 	... 43 more

- verify_outbox: PASS
- upload: NOT_RUN — Stage did not execute
- verify_digest: NOT_RUN — Stage did not execute
- download: NOT_RUN — Stage did not execute
- before: NOT_RUN — Stage did not execute
- after: NOT_RUN — Stage did not execute
- replay: NOT_RUN — Stage did not execute
- generate_reports: NOT_RUN — Stage did not execute

### RECORD_PAYMENT_METHOD_CHANGE
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

### RECORD_AMOUNT_CORRECTION
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

### OPEN_CASH_SHIFT
- status: BLOCKED
- failure reason: Normal app sync did not acknowledge the expense: acknowledged=0; supabaseUserId=bdc76195-183e-4b72-a701-6385ca29dffd; supabaseSessionPresent=true; adminScopeAuthorized=true; activeUserId=bdc76195-183e-4b72-a701-6385ca29dffd; activeRole=ADMIN; activeMembershipId=cert-membership-bdc76195183e; activeBusinessId=cert-business-bdc76195183e; activeBranchId=cert-branch-bdc76195183e; expectedUserId=bdc76195-183e-4b72-a701-6385ca29dffd; expectedMembershipId=cert-membership-bdc76195183e; expectedBusinessId=cert-business-bdc76195183e; expectedBranchId=cert-branch-bdc76195183e; outboxAfterSyncStatus=WAITING_RETRY; outboxAfterSyncActor=bdc76195-183e-4b72-a701-6385ca29dffd; outboxAfterSyncBusiness=cert-business-bdc76195183e; outboxAfterSyncBranch=cert-branch-bdc76195183e; outboxAfterSyncAttempts=1; syncFailureLog=5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:94)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source:1)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source:1)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest.recordExpense_and_recordSale_pass_through_real_driver_in_one_run(RecordExpenseCertificationTest.kt:23)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at java.lang.reflect.Method.invoke(Native Method)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:56)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner$1.evaluate(BlockJUnit4ClassRunner.java:100)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:366)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:103)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:63)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at androidx.test.ext.junit.runners.AndroidJUnit4.run(AndroidJUnit4.java:162)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:128)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:27)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:137)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:115)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:68)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:59)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at androidx.test.runner.AndroidJUnitRunner.onStart(AndroidJUnitRunner.java:463)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at android.app.Instrumentation$InstrumentationThread.run(Instrumentation.java:2597)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: Caused by: java.lang.IllegalArgumentException: operationId rejected by validId: value="cert-open-bdc76195183e", length=22, maxLength=128, expectedFormat=^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncContractKt.validId(FinancialSyncContract.kt:13)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncContractKt.access$validId(FinancialSyncContract.kt:1)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncIdentity.<init>(FinancialSyncContract.kt:31)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine.syncOutbound(FinancialSyncEngine.kt:267)
10-23 03:52:07.937  5089  5107 E PaidDueFinancialSync: 	... 43 more

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
- failure reason: Normal app sync did not acknowledge the expense: acknowledged=0; supabaseUserId=53bd84dd-fb6a-4880-8860-bb20bfda4d9e; supabaseSessionPresent=true; adminScopeAuthorized=true; activeUserId=53bd84dd-fb6a-4880-8860-bb20bfda4d9e; activeRole=ADMIN; activeMembershipId=cert-membership-53bd84ddfb6a; activeBusinessId=cert-business-53bd84ddfb6a; activeBranchId=cert-branch-53bd84ddfb6a; expectedUserId=53bd84dd-fb6a-4880-8860-bb20bfda4d9e; expectedMembershipId=cert-membership-53bd84ddfb6a; expectedBusinessId=cert-business-53bd84ddfb6a; expectedBranchId=cert-branch-53bd84ddfb6a; outboxAfterSyncStatus=WAITING_RETRY; outboxAfterSyncActor=53bd84dd-fb6a-4880-8860-bb20bfda4d9e; outboxAfterSyncBusiness=cert-business-53bd84ddfb6a; outboxAfterSyncBranch=cert-branch-53bd84ddfb6a; outboxAfterSyncAttempts=1; syncFailureLog=089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:94)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source:1)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source:1)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest.recordExpense_and_recordSale_pass_through_real_driver_in_one_run(RecordExpenseCertificationTest.kt:23)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at java.lang.reflect.Method.invoke(Native Method)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:56)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner$1.evaluate(BlockJUnit4ClassRunner.java:100)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:366)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:103)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:63)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at androidx.test.ext.junit.runners.AndroidJUnit4.run(AndroidJUnit4.java:162)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:128)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:27)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:137)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:115)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:68)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:59)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at androidx.test.runner.AndroidJUnitRunner.onStart(AndroidJUnitRunner.java:463)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at android.app.Instrumentation$InstrumentationThread.run(Instrumentation.java:2597)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: Caused by: java.lang.IllegalArgumentException: operationId rejected by validId: value="cert-count-53bd84ddfb6a", length=23, maxLength=128, expectedFormat=^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncContractKt.validId(FinancialSyncContract.kt:13)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncContractKt.access$validId(FinancialSyncContract.kt:1)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncIdentity.<init>(FinancialSyncContract.kt:31)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine.syncOutbound(FinancialSyncEngine.kt:267)
10-23 03:52:17.620  5089  5107 E PaidDueFinancialSync: 	... 43 more

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
- failure reason: Normal app sync did not acknowledge the expense: acknowledged=0; supabaseUserId=8f9ff9b3-8e33-4d4e-b7f2-a4dae26af9a4; supabaseSessionPresent=true; adminScopeAuthorized=true; activeUserId=8f9ff9b3-8e33-4d4e-b7f2-a4dae26af9a4; activeRole=ADMIN; activeMembershipId=cert-membership-8f9ff9b38e33; activeBusinessId=cert-business-8f9ff9b38e33; activeBranchId=cert-branch-8f9ff9b38e33; expectedUserId=8f9ff9b3-8e33-4d4e-b7f2-a4dae26af9a4; expectedMembershipId=cert-membership-8f9ff9b38e33; expectedBusinessId=cert-business-8f9ff9b38e33; expectedBranchId=cert-branch-8f9ff9b38e33; outboxAfterSyncStatus=WAITING_RETRY; outboxAfterSyncActor=8f9ff9b3-8e33-4d4e-b7f2-a4dae26af9a4; outboxAfterSyncBusiness=cert-business-8f9ff9b38e33; outboxAfterSyncBranch=cert-branch-8f9ff9b38e33; outboxAfterSyncAttempts=1; syncFailureLog=089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:94)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source:1)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source:1)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest.recordExpense_and_recordSale_pass_through_real_driver_in_one_run(RecordExpenseCertificationTest.kt:23)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at java.lang.reflect.Method.invoke(Native Method)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:56)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner$1.evaluate(BlockJUnit4ClassRunner.java:100)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:366)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:103)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:63)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at androidx.test.ext.junit.runners.AndroidJUnit4.run(AndroidJUnit4.java:162)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:128)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:27)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:137)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:115)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:68)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:59)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at androidx.test.runner.AndroidJUnitRunner.onStart(AndroidJUnitRunner.java:463)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: 	at android.app.Instrumentation$InstrumentationThread.run(Instrumentation.java:2597)
10-23 03:52:27.881  5089  5107 E PaidDueFinancialSync: Caused by: java.lang.IllegalArgumentException: operationId rejected by validId: value="cert-close-8f9ff9b38e33", length=23, maxLength=128, expectedFormat=^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$
10-23 03:52:27.882  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncContractKt.validId(FinancialSyncContract.kt:13)
10-23 03:52:27.882  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncContractKt.access$validId(FinancialSyncContract.kt:1)
10-23 03:52:27.882  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncIdentity.<init>(FinancialSyncContract.kt:31)
10-23 03:52:27.882  5089  5107 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine.syncOutbound(FinancialSyncEngine.kt:267)
10-23 03:52:27.882  5089  5107 E PaidDueFinancialSync: 	... 43 more

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
> Task :app:transformDebugAndroidTestClassesWithAsm
> Task :app:dexBuilderDebugAndroidTest
> Task :app:mergeProjectDexDebugAndroidTest
> Task :app:packageDebugAndroidTest
> Task :app:assembleDebugAndroidTest
> Task :app:createDebugAndroidTestApkListingFileRedirect
gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_4-1792727147608.json

BUILD SUCCESSFUL in 4m 35s
81 actionable tasks: 81 executed
Configuration cache entry stored.
Performing Streamed Install
Success
Performing Streamed Install
Success

com.dailymoneyregister.core.cloud.sync.TwoDatabaseFinancialCertificationTest:....

Time: 0.388

OK (4 tests)



com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest:..

Time: 139.548

OK (2 tests)


/sdcard/Android/data/com.dailymoneyregister.debug/files/financial-certification-evidence/: 131 files pulled, 0 skipped. 0.2 MB/s (143940 bytes in 0.894s)
::error::Financial certification did not PASS. Evidence collection alone cannot satisfy the release gate.
::error::RECORD_CREDIT_REPAYMENT: BLOCKED
::error::RECORD_COUPON_PURCHASE: BLOCKED
::error::OPEN_CASH_SHIFT: BLOCKED
::error::COUNT_CASH_SHIFT: BLOCKED
::error::CLOSE_CASH_SHIFT: BLOCKED
::error::RECORD_EXPENSE_CORRECTION: BLOCKED
::error::RECORD_EXPENSE_VOID: BLOCKED
```

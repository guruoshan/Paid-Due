# Financial certification result

- Commit SHA: b8f81306e6ef8e9bfcb8299dd4be3147a6ba9d83
- Time (UTC): 2026-10-22T13:52:54Z
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
- failure reason: Normal app sync did not acknowledge the expense: acknowledged=0; supabaseUserId=43ae2a0c-7b9c-4f8b-95f4-d5245e81a61c; supabaseSessionPresent=true; adminScopeAuthorized=true; activeUserId=43ae2a0c-7b9c-4f8b-95f4-d5245e81a61c; activeRole=ADMIN; activeMembershipId=cert-membership-43ae2a0c7b9c; activeBusinessId=cert-business-43ae2a0c7b9c; activeBranchId=cert-branch-43ae2a0c7b9c; expectedUserId=43ae2a0c-7b9c-4f8b-95f4-d5245e81a61c; expectedMembershipId=cert-membership-43ae2a0c7b9c; expectedBusinessId=cert-business-43ae2a0c7b9c; expectedBranchId=cert-branch-43ae2a0c7b9c; outboxAfterSyncStatus=WAITING_RETRY; outboxAfterSyncActor=43ae2a0c-7b9c-4f8b-95f4-d5245e81a61c; outboxAfterSyncBusiness=cert-business-43ae2a0c7b9c; outboxAfterSyncBranch=cert-branch-43ae2a0c7b9c; outboxAfterSyncAttempts=1; syncFailureLog=e.cloud.sync.FinancialSyncContractKt.access$validId(FinancialSyncContract.kt:1)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncIdentity.<init>(FinancialSyncContract.kt:26)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine.syncOutbound(FinancialSyncEngine.kt:266)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine$syncOutbound$1.invokeSuspend(Unknown Source:15)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:34)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.EventLoopImplBase.processNextEvent(EventLoop.common.kt:263)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:94)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source:1)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source:1)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest.recordExpense_and_recordSale_pass_through_real_driver_in_one_run(RecordExpenseCertificationTest.kt:23)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at java.lang.reflect.Method.invoke(Native Method)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:56)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner$1.evaluate(BlockJUnit4ClassRunner.java:100)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:366)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:103)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:63)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at androidx.test.ext.junit.runners.AndroidJUnit4.run(AndroidJUnit4.java:162)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:128)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:27)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:137)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:115)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:68)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:59)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at androidx.test.runner.AndroidJUnitRunner.onStart(AndroidJUnitRunner.java:463)
10-22 13:51:55.423  4706  4719 E PaidDueFinancialSync: 	at android.app.Instrumentation$InstrumentationThread.run(Instrumentation.java:2597)

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
- failure reason: Normal app sync did not acknowledge the expense: acknowledged=0; supabaseUserId=1d85940c-0257-4703-b972-6104f5b471ba; supabaseSessionPresent=true; adminScopeAuthorized=true; activeUserId=1d85940c-0257-4703-b972-6104f5b471ba; activeRole=ADMIN; activeMembershipId=cert-membership-1d85940c0257; activeBusinessId=cert-business-1d85940c0257; activeBranchId=cert-branch-1d85940c0257; expectedUserId=1d85940c-0257-4703-b972-6104f5b471ba; expectedMembershipId=cert-membership-1d85940c0257; expectedBusinessId=cert-business-1d85940c0257; expectedBranchId=cert-branch-1d85940c0257; outboxAfterSyncStatus=WAITING_RETRY; outboxAfterSyncActor=1d85940c-0257-4703-b972-6104f5b471ba; outboxAfterSyncBusiness=cert-business-1d85940c0257; outboxAfterSyncBranch=cert-branch-1d85940c0257; outboxAfterSyncAttempts=1; syncFailureLog=e.cloud.sync.FinancialSyncContractKt.access$validId(FinancialSyncContract.kt:1)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncIdentity.<init>(FinancialSyncContract.kt:26)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine.syncOutbound(FinancialSyncEngine.kt:266)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine$syncOutbound$1.invokeSuspend(Unknown Source:15)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:34)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.EventLoopImplBase.processNextEvent(EventLoop.common.kt:263)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:94)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source:1)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source:1)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest.recordExpense_and_recordSale_pass_through_real_driver_in_one_run(RecordExpenseCertificationTest.kt:23)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at java.lang.reflect.Method.invoke(Native Method)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:56)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner$1.evaluate(BlockJUnit4ClassRunner.java:100)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:366)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:103)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:63)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at androidx.test.ext.junit.runners.AndroidJUnit4.run(AndroidJUnit4.java:162)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:128)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:27)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:137)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:115)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:68)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:59)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at androidx.test.runner.AndroidJUnitRunner.onStart(AndroidJUnitRunner.java:463)
10-22 13:52:03.327  4706  4719 E PaidDueFinancialSync: 	at android.app.Instrumentation$InstrumentationThread.run(Instrumentation.java:2597)

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
- failure reason: Normal app sync did not acknowledge the expense: acknowledged=0; supabaseUserId=683eea2f-871a-4cbf-a875-609787a8bc26; supabaseSessionPresent=true; adminScopeAuthorized=true; activeUserId=683eea2f-871a-4cbf-a875-609787a8bc26; activeRole=ADMIN; activeMembershipId=cert-membership-683eea2f871a; activeBusinessId=cert-business-683eea2f871a; activeBranchId=cert-branch-683eea2f871a; expectedUserId=683eea2f-871a-4cbf-a875-609787a8bc26; expectedMembershipId=cert-membership-683eea2f871a; expectedBusinessId=cert-business-683eea2f871a; expectedBranchId=cert-branch-683eea2f871a; outboxAfterSyncStatus=WAITING_RETRY; outboxAfterSyncActor=683eea2f-871a-4cbf-a875-609787a8bc26; outboxAfterSyncBusiness=cert-business-683eea2f871a; outboxAfterSyncBranch=cert-branch-683eea2f871a; outboxAfterSyncAttempts=1; syncFailureLog=e.cloud.sync.FinancialSyncContractKt.access$validId(FinancialSyncContract.kt:1)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncIdentity.<init>(FinancialSyncContract.kt:26)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine.syncOutbound(FinancialSyncEngine.kt:266)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine$syncOutbound$1.invokeSuspend(Unknown Source:15)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:34)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.EventLoopImplBase.processNextEvent(EventLoop.common.kt:263)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:94)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source:1)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source:1)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest.recordExpense_and_recordSale_pass_through_real_driver_in_one_run(RecordExpenseCertificationTest.kt:23)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at java.lang.reflect.Method.invoke(Native Method)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:56)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner$1.evaluate(BlockJUnit4ClassRunner.java:100)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:366)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:103)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:63)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at androidx.test.ext.junit.runners.AndroidJUnit4.run(AndroidJUnit4.java:162)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:128)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:27)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:137)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:115)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:68)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:59)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at androidx.test.runner.AndroidJUnitRunner.onStart(AndroidJUnitRunner.java:463)
10-22 13:52:35.123  4706  4719 E PaidDueFinancialSync: 	at android.app.Instrumentation$InstrumentationThread.run(Instrumentation.java:2597)

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
- failure reason: Normal app sync did not acknowledge the expense: acknowledged=0; supabaseUserId=b6399df7-1724-4155-9b90-fcfae1086e90; supabaseSessionPresent=true; adminScopeAuthorized=true; activeUserId=b6399df7-1724-4155-9b90-fcfae1086e90; activeRole=ADMIN; activeMembershipId=cert-membership-b6399df71724; activeBusinessId=cert-business-b6399df71724; activeBranchId=cert-branch-b6399df71724; expectedUserId=b6399df7-1724-4155-9b90-fcfae1086e90; expectedMembershipId=cert-membership-b6399df71724; expectedBusinessId=cert-business-b6399df71724; expectedBranchId=cert-branch-b6399df71724; outboxAfterSyncStatus=WAITING_RETRY; outboxAfterSyncActor=b6399df7-1724-4155-9b90-fcfae1086e90; outboxAfterSyncBusiness=cert-business-b6399df71724; outboxAfterSyncBranch=cert-branch-b6399df71724; outboxAfterSyncAttempts=1; syncFailureLog=e.cloud.sync.FinancialSyncContractKt.access$validId(FinancialSyncContract.kt:1)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncIdentity.<init>(FinancialSyncContract.kt:26)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine.syncOutbound(FinancialSyncEngine.kt:266)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine$syncOutbound$1.invokeSuspend(Unknown Source:15)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:34)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.EventLoopImplBase.processNextEvent(EventLoop.common.kt:263)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:94)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source:1)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source:1)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest.recordExpense_and_recordSale_pass_through_real_driver_in_one_run(RecordExpenseCertificationTest.kt:23)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at java.lang.reflect.Method.invoke(Native Method)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:56)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner$1.evaluate(BlockJUnit4ClassRunner.java:100)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:366)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:103)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:63)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at androidx.test.ext.junit.runners.AndroidJUnit4.run(AndroidJUnit4.java:162)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:128)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:27)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:137)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:115)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:68)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:59)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at androidx.test.runner.AndroidJUnitRunner.onStart(AndroidJUnitRunner.java:463)
10-22 13:52:42.148  4706  4719 E PaidDueFinancialSync: 	at android.app.Instrumentation$InstrumentationThread.run(Instrumentation.java:2597)

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
- failure reason: Normal app sync did not acknowledge the expense: acknowledged=0; supabaseUserId=2b9a92f4-53ec-474e-968f-c5e8809b9433; supabaseSessionPresent=true; adminScopeAuthorized=true; activeUserId=2b9a92f4-53ec-474e-968f-c5e8809b9433; activeRole=ADMIN; activeMembershipId=cert-membership-2b9a92f453ec; activeBusinessId=cert-business-2b9a92f453ec; activeBranchId=cert-branch-2b9a92f453ec; expectedUserId=2b9a92f4-53ec-474e-968f-c5e8809b9433; expectedMembershipId=cert-membership-2b9a92f453ec; expectedBusinessId=cert-business-2b9a92f453ec; expectedBranchId=cert-branch-2b9a92f453ec; outboxAfterSyncStatus=WAITING_RETRY; outboxAfterSyncActor=2b9a92f4-53ec-474e-968f-c5e8809b9433; outboxAfterSyncBusiness=cert-business-2b9a92f453ec; outboxAfterSyncBranch=cert-branch-2b9a92f453ec; outboxAfterSyncAttempts=1; syncFailureLog=e.cloud.sync.FinancialSyncContractKt.access$validId(FinancialSyncContract.kt:1)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncIdentity.<init>(FinancialSyncContract.kt:26)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine.syncOutbound(FinancialSyncEngine.kt:266)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.FinancialSyncEngine$syncOutbound$1.invokeSuspend(Unknown Source:15)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:34)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.EventLoopImplBase.processNextEvent(EventLoop.common.kt:263)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:94)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source:1)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source:1)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest.recordExpense_and_recordSale_pass_through_real_driver_in_one_run(RecordExpenseCertificationTest.kt:23)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at java.lang.reflect.Method.invoke(Native Method)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.model.FrameworkMethod.invokeExplosively(FrameworkMethod.java:56)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.internal.runners.statements.InvokeMethod.evaluate(InvokeMethod.java:17)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner$1.evaluate(BlockJUnit4ClassRunner.java:100)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runLeaf(ParentRunner.java:366)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:103)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.BlockJUnit4ClassRunner.runChild(BlockJUnit4ClassRunner.java:63)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at androidx.test.ext.junit.runners.AndroidJUnit4.run(AndroidJUnit4.java:162)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:128)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.Suite.runChild(Suite.java:27)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$4.run(ParentRunner.java:331)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:79)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.runChildren(ParentRunner.java:329)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.access$100(ParentRunner.java:66)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$2.evaluate(ParentRunner.java:293)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner$3.evaluate(ParentRunner.java:306)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runners.ParentRunner.run(ParentRunner.java:413)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:137)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at org.junit.runner.JUnitCore.run(JUnitCore.java:115)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:68)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at androidx.test.internal.runner.TestExecutor.execute(TestExecutor.java:59)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at androidx.test.runner.AndroidJUnitRunner.onStart(AndroidJUnitRunner.java:463)
10-22 13:52:50.301  4706  4719 E PaidDueFinancialSync: 	at android.app.Instrumentation$InstrumentationThread.run(Instrumentation.java:2597)

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
gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_4-1792676896168.json

BUILD SUCCESSFUL in 2m 51s
81 actionable tasks: 81 executed
Configuration cache entry stored.
Performing Streamed Install
Success
Performing Streamed Install
Success

com.dailymoneyregister.core.cloud.sync.TwoDatabaseFinancialCertificationTest:....

Time: 0.293

OK (4 tests)



com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest:..

Time: 113.414

OK (2 tests)


/sdcard/Android/data/com.dailymoneyregister.debug/files/financial-certification-evidence/: 131 files pulled, 0 skipped. 0.2 MB/s (143940 bytes in 0.588s)
::error::Financial certification did not PASS. Evidence collection alone cannot satisfy the release gate.
::error::RECORD_CREDIT_REPAYMENT: BLOCKED
::error::RECORD_COUPON_PURCHASE: BLOCKED
::error::OPEN_CASH_SHIFT: BLOCKED
::error::COUNT_CASH_SHIFT: BLOCKED
::error::CLOSE_CASH_SHIFT: BLOCKED
::error::RECORD_EXPENSE_CORRECTION: BLOCKED
::error::RECORD_EXPENSE_VOID: BLOCKED
```

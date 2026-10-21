# Latest financial certification result

- Commit SHA: 66d8061f8dd8a590d1cd756cb1108b6b518e7bde
- Time (UTC): 2026-10-21T12:22:15Z
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
- failure reason: Value {"operation_id":null,"run_id":"cert-expense-operation","schema_version":1,"tables":{"amount_correction_events":{"primary_key":"id","row_count":0,"rows":[]},"cash_shift_events":{"primary_key":"id","row_count":1,"rows":[{"actor_user_id":"1b1e6474-a6d2-41f9-abda-145fd5ed2224","amount_minor":0,"id":"01a15e69-6bef-7f49-b201-93692dac8ea9:OPENED","occurred_at":1792585329630,"operation_id":"01a15e69-6bef-7f49-b201-93692dac8ea9","shift_id":"01a15e69-6bef-71aa-895f-bc614aa4fe60","type":"OPENED"}]},"cash_shifts":{"primary_key":"id","row_count":1,"rows":[{"branch_id":"cert-branch","created_at":1792585329630,"id":"01a15e69-6bef-71aa-895f-bc614aa4fe60","user_id":"1b1e6474-a6d2-41f9-abda-145fd5ed2224"}]},"coupon_ledger_events":{"primary_key":"id","row_count":0,"rows":[]},"coupon_wallets":{"primary_key":"id","row_count":0,"rows":[]},"credit_ledger_events":{"primary_key":"id","row_count":0,"rows":[]},"credit_repayment_allocations":{"primary_key":"id","row_count":0,"rows":[]},"credit_repayments":{"primary_key":"id","row_count":0,"rows":[]},"expense_events":{"primary_key":"id","row_count":0,"rows":[]},"expenses":{"primary_key":"id","row_count":1,"rows":[{"actor_user_id":"1b1e6474-a6d2-41f9-abda-145fd5ed2224","amount_minor":1250,"branch_id":"cert-branch","business_id":"cert-business","cash_shift_id":"01a15e69-6bef-71aa-895f-bc614aa4fe60","category":"SUPPLIES","created_at":1792585329630,"currency_code":"OMR","id":"cert-expense","note":"Synthetic certification fixture","occurred_at":1792585329630,"operation_id":"cert-expense-operation","payee_account_snapshot":null,"payee_id":null,"payee_name_snapshot":"Certification Supplier","payee_phone_snapshot":null,"payment_category":"CASH","recurrence_id":null,"reference":"CERT-EXPENSE"}]},"operations":{"primary_key":"id","row_count":2,"rows":[{"actor_user_id":"1b1e6474-a6d2-41f9-abda-145fd5ed2224","branch_id":"cert-branch","business_id":"cert-business","cash_shift_id":null,"command_schema_version":1,"device_id":"cert-device-a","id":"01a15e69-6bef-7f49-b201-93692dac8ea9","intent_payload":"v=1\ncommandType=OPEN_REGISTER_SHIFT\noperationId=01a15e69-6bef-7f49-b201-93692dac8ea9\nbusinessId=cert-business\nbranchId=cert-branch\nshiftId=01a15e69-6bef-71aa-895f-bc614aa4fe60\nactorUserId=1b1e6474-a6d2-41f9-abda-145fd5ed2224\ndeviceId=cert-device-a\noccurredAt=1792585329630\n","occurred_at":1792585329630,"operation_type":"OPEN_CASH_SHIFT","reason":null,"request_fingerprint":"9aab44016ee20bf326ab1a419ad62bb1c06f3d9ee9b217a7fb6d9156f87e96b5","result_reference_id":"01a15e69-6bef-71aa-895f-bc614aa4fe60","result_reference_type":"CASH_SHIFT","status":"SUCCEEDED","subject_id":"01a15e69-6bef-71aa-895f-bc614aa4fe60","subject_type":"CASH_SHIFT","transaction_id":null},{"actor_user_id":"1b1e6474-a6d2-41f9-abda-145fd5ed2224","branch_id":"cert-branch","business_id":"cert-business","cash_shift_id":"01a15e69-6bef-71aa-895f-bc614aa4fe60","command_schema_version":1,"device_id":"cert-device-a","id":"cert-expense-operation","intent_payload":"ZXhwZW5zZS12MQ==|Y2VydC1leHBlbnNlLW9wZXJhdGlvbg==|Y2VydC1idXNpbmVzcw==|Y2VydC1icmFuY2g=|MWIxZTY0NzQtYTZkMi00MWY5LWFiZGEtMTQ1ZmQ1ZWQyMjI0|Y2VydC1leHBlbnNl|MTI1MA==|T01S|U1VQUExJRVM=|Q0FTSA==|Q0FTSF9URU5ERVI=||Q2VydGlmaWNhdGlvbiBTdXBwbGllcg==|||Q0VSVC1FWFBFTlNF|U3ludGhldGljIGNlcnRpZmljYXRpb24gZml4dHVyZQ==||Y2VydC1kZXZpY2UtYQ==|MTc5MjU4NTMyOTYzMA==","occurred_at":1792585329630,"operation_type":"RECORD_EXPENSE","reason":null,"request_fingerprint":"3a2a06cdbd569724ff87dc500e46b9cb488236c38e98d5e18e4e4e00e27937d9","result_reference_id":"cert-expense","result_reference_type":"EXPENSE","status":"SUCCEEDED","subject_id":"cert-expense","subject_type":"EXPENSE","transaction_id":null}]},"payment_method_change_events":{"primary_key":"id","row_count":0,"rows":[]},"refund_events":{"primary_key":"id","row_count":0,"rows":[]},"settlement_entries":{"primary_key":"id","row_count":1,"rows":[{"actor_user_id":"1b1e6474-a6d2-41f9-abda-145fd5ed2224","amount_signed_minor":-1250,"category":"CASH","channel":"CASH_TENDER","confirmation_type":"CASHIER_CONFIRMED","currency_code":"OMR","entry_purpose":"EXPENSE_OUTFLOW","id":"cert-expense-operation:expense-outflow","occurred_at":1792585329630,"operation_id":"cert-expense-operation","provider_ref":"CERT-EXPENSE","qr_profile_id_snapshot":null,"subject_id":"cert-expense","subject_type":"EXPENSE","transaction_id":null}]},"transaction_items":{"primary_key":"id","row_count":0,"rows":[]},"transactions":{"primary_key":"id","row_count":0,"rows":[]},"void_events":{"primary_key":"id","row_count":0,"rows":[]}}} of type java.lang.String cannot be converted to JSONObject
- verify_outbox: PASS
- upload: PASS
- verify_digest: PASS
- download: PASS
- before: PASS
- after: PASS
- replay: PASS
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


Time: 11.804
There was 1 failure:
1) recordExpense_passes_through_real_driver(com.dailymoneyregister.core.cloud.sync.RecordExpenseCertificationTest)
java.lang.AssertionError: RECORD_EXPENSE must pass every real execution stage; blocker=Value {"operation_id":null,"run_id":"cert-expense-operation","schema_version":1,"tables":{"amount_correction_events":{"primary_key":"id","row_count":0,"rows":[]},"cash_shift_events":{"primary_key":"id","row_count":1,"rows":[{"actor_user_id":"1b1e6474-a6d2-41f9-abda-145fd5ed2224","amount_minor":0,"id":"01a15e69-6bef-7f49-b201-93692dac8ea9:OPENED","occurred_at":1792585329630,"operation_id":"01a15e69-6bef-7f49-b201-93692dac8ea9","shift_id":"01a15e69-6bef-71aa-895f-bc614aa4fe60","type":"OPENED"}]},"cash_shifts":{"primary_key":"id","row_count":1,"rows":[{"branch_id":"cert-branch","created_at":1792585329630,"id":"01a15e69-6bef-71aa-895f-bc614aa4fe60","user_id":"1b1e6474-a6d2-41f9-abda-145fd5ed2224"}]},"coupon_ledger_events":{"primary_key":"id","row_count":0,"rows":[]},"coupon_wallets":{"primary_key":"id","row_count":0,"rows":[]},"credit_ledger_events":{"primary_key":"id","row_count":0,"rows":[]},"credit_repayment_allocations":{"primary_key":"id","row_count":0,"rows":[]},"credit_repayments":{"primary_key":"id","row_count":0,"rows":[]},"expense_events":{"primary_key":"id","row_count":0,"rows":[]},"expenses":{"primary_key":"id","row_count":1,"rows":[{"actor_user_id":"1b1e6474-a6d2-41f9-abda-145fd5ed2224","amount_minor":1250,"branch_id":"cert-branch","business_id":"cert-business","cash_shift_id":"01a15e69-6bef-71aa-895f-bc614aa4fe60","category":"SUPPLIES","created_at":1792585329630,"currency_code":"OMR","id":"cert-expense","note":"Synthetic certification fixture","occurred_at":1792585329630,"operation_id":"cert-expense-operation","payee_account_snapshot":null,"payee_id":null,"payee_name_snapshot":"Certification Supplier","payee_phone_snapshot":null,"payment_category":"CASH","recurrence_id":null,"reference":"CERT-EXPENSE"}]},"operations":{"primary_key":"id","row_count":2,"rows":[{"actor_user_id":"1b1e6474-a6d2-41f9-abda-145fd5ed2224","branch_id":"cert-branch","business_id":"cert-business","cash_shift_id":null,"command_schema_version":1,"device_id":"cert-device-a","id":"01a15e69-6bef-7f49-b201-93692dac8ea9","intent_payload":"v=1\ncommandType=OPEN_REGISTER_SHIFT\noperationId=01a15e69-6bef-7f49-b201-93692dac8ea9\nbusinessId=cert-business\nbranchId=cert-branch\nshiftId=01a15e69-6bef-71aa-895f-bc614aa4fe60\nactorUserId=1b1e6474-a6d2-41f9-abda-145fd5ed2224\ndeviceId=cert-device-a\noccurredAt=1792585329630\n","occurred_at":1792585329630,"operation_type":"OPEN_CASH_SHIFT","reason":null,"request_fingerprint":"9aab44016ee20bf326ab1a419ad62bb1c06f3d9ee9b217a7fb6d9156f87e96b5","result_reference_id":"01a15e69-6bef-71aa-895f-bc614aa4fe60","result_reference_type":"CASH_SHIFT","status":"SUCCEEDED","subject_id":"01a15e69-6bef-71aa-895f-bc614aa4fe60","subject_type":"CASH_SHIFT","transaction_id":null},{"actor_user_id":"1b1e6474-a6d2-41f9-abda-145fd5ed2224","branch_id":"cert-branch","business_id":"cert-business","cash_shift_id":"01a15e69-6bef-71aa-895f-bc614aa4fe60","command_schema_version":1,"device_id":"cert-device-a","id":"cert-expense-operation","intent_payload":"ZXhwZW5zZS12MQ==|Y2VydC1leHBlbnNlLW9wZXJhdGlvbg==|Y2VydC1idXNpbmVzcw==|Y2VydC1icmFuY2g=|MWIxZTY0NzQtYTZkMi00MWY5LWFiZGEtMTQ1ZmQ1ZWQyMjI0|Y2VydC1leHBlbnNl|MTI1MA==|T01S|U1VQUExJRVM=|Q0FTSA==|Q0FTSF9URU5ERVI=||Q2VydGlmaWNhdGlvbiBTdXBwbGllcg==|||Q0VSVC1FWFBFTlNF|U3ludGhldGljIGNlcnRpZmljYXRpb24gZml4dHVyZQ==||Y2VydC1kZXZpY2UtYQ==|MTc5MjU4NTMyOTYzMA==","occurred_at":1792585329630,"operation_type":"RECORD_EXPENSE","reason":null,"request_fingerprint":"3a2a06cdbd569724ff87dc500e46b9cb488236c38e98d5e18e4e4e00e27937d9","result_reference_id":"cert-expense","result_reference_type":"EXPENSE","status":"SUCCEEDED","subject_id":"cert-expense","subject_type":"EXPENSE","transaction_id":null}]},"payment_method_change_events":{"primary_key":"id","row_count":0,"rows":[]},"refund_events":{"primary_key":"id","row_count":0,"rows":[]},"settlement_entries":{"primary_key":"id","row_count":1,"rows":[{"actor_user_id":"1b1e6474-a6d2-41f9-abda-145fd5ed2224","amount_signed_minor":-1250,"category":"CASH","channel":"CASH_TENDER","confirmation_type":"CASHIER_CONFIRMED","currency_code":"OMR","entry_purpose":"EXPENSE_OUTFLOW","id":"cert-expense-operation:expense-outflow","occurred_at":1792585329630,"operation_id":"cert-expense-operation","provider_ref":"CERT-EXPENSE","qr_profile_id_snapshot":null,"subject_id":"cert-expense","subject_type":"EXPENSE","transaction_id":null}]},"transaction_items":{"primary_key":"id","row_count":0,"rows":[]},"transactions":{"primary_key":"id","row_count":0,"rows":[]},"void_events":{"primary_key":"id","row_count":0,"rows":[]}}} of type java.lang.String cannot be converted to JSONObject expected:<PASS> but was:<FAIL>
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


/sdcard/Android/data/com.dailymoneyregister.debug/files/financial-certification-evidence/: 131 files pulled, 0 skipped. 0.0 MB/s (30629 bytes in 0.683s)
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

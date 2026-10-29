# Cash-shift OPERATION_COLLISION reconciliation plan

## Scope

This plan addresses collisions for `OPEN_CASH_SHIFT`, `COUNT_CASH_SHIFT`, and `CLOSE_CASH_SHIFT` where the stored canonical payload has `cash_shift_id = ""` but a replay includes the actual shift ID.

The client fix canonicalizes `cash_shift_id` to an empty string for those three lifecycle operation types. Their authoritative shift identity remains `subject_id`. The operation ID, request fingerprint, digest algorithm, server collision guard, and existing audit records remain unchanged.

## Recovery procedure

1. Preserve all `private.collision_audit` rows and stored server envelopes. Do not delete or rewrite evidence to clear the symptom.
2. Inventory affected rows by business and operation ID. Export/retain stored and incoming digests, canonical payloads, device IDs, timestamps, operation type, and field-level differences.
3. For each operation, verify the tenant/business and branch, actor, operation type, `subject_type = CASH_SHIFT`, and that `subject_id` identifies the intended shift. Confirm the only canonical difference is `cash_shift_id`; investigate any additional differences separately.
4. Release the client fix and retry the existing outbox operation using its original operation ID. The corrected codec should produce the same lifecycle representation as the stored envelope when all other fields match.
5. Accept acknowledgement only through the existing server idempotency path when the canonical digest matches. Do not force-acknowledge a collision, regenerate IDs, or bypass digest comparison.
6. Keep any operation with remaining differences in attention/reconciliation state. Reconcile against authoritative shift events and audit evidence before any exceptional repair; document the evidence and preserve the original envelope and collision record.
7. Verify that retries are acknowledged, the affected outbox entries reach their expected terminal state, both devices converge on the same shift history, and no new collisions arise for the same operations.

## Safety constraints

- No `collision_audit` deletion or mutation.
- No operation-ID regeneration.
- No changes to request fingerprints or digest algorithm.
- No weakening of `ingest_financial_envelope` collision detection.
- No blind database rewrite of stored canonical payloads.

## Existing collision guard regression

The SQL idempotency test `supabase/tests/20261027193000_financial_envelope_idempotency.sql` already asserts that changed content for an existing operation returns `OPERATION_COLLISION` and is recorded in `private.collision_audit`. Keep that test and guard unchanged. The Android codec regression tests additionally assert that a genuine financial payload change changes the canonical digest.

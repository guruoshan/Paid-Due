---
name: offline-sync-performance
description: Offline-first architecture, WorkManager synchronization, Room local storage caching, network resilience, and conflict resolution strategies. Use when implementing offline queueing, background sync tasks, network state handling, or conflict resolution logic.
---

# Offline Sync & Performance

## Activation Criteria
- Implementing offline-first data caching, Room database persistence, or sync queues.
- Scheduling background sync operations using Android `WorkManager`.
- Managing network transition states, retry backoffs, or data conflict resolution.

## Engineering Standards
- **Local-First Writes**: Immediately commit user operations to local Room database before attempting network dispatch. Maintain explicit sync state flags (`PENDING`, `SYNCED`, `FAILED`).
- **WorkManager Sync**: Enforce network constraints (`NetworkType.CONNECTED`) and exponential backoff retry policies for background synchronization jobs.
- **Idempotency**: Include client-generated UUIDs and client timestamps with sync payloads to guarantee idempotent processing on server endpoints.
- **Conflict Resolution**: Implement explicit conflict policies (e.g., Last-Write-Wins or client/server merge rules) to prevent data corruption during reconnects.
- **Resource Efficiency**: Batch sync requests to save battery and cellular data. Respect system low-power modes and background execution limits.

## Verification Steps
1. Test offline scenario: disable network, write data locally, re-enable network, and verify automatic background sync execution.
2. Simulate duplicate sync payloads to verify idempotent handling.
3. Validate WorkManager execution constraints and queue persistence across app restarts.

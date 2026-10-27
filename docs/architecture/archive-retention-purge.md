# Conversation platform: archive, retention and purge

Design document (master spec 5.6, 16). Plan 02 implements resolve, archive, unarchive and reopen; Plan 05 T1/T2 the
retention policy, the bounded purge and its admin API. Conventions (Existing, `D<n>`, `R<n>`):
[system-overview.md](system-overview.md) section 0.

**Existing:** nothing is ever deleted today: there is no purge, archive or TTL job, and `CLOSED` is allowed by the V2
check but never written ([workspace-inventory.md](../research/workspace-inventory.md) sections 5.1, 8.2).

## 1. Five different operations

| Operation | Endpoint / actor | What changes | What is kept | Reversible | Audit |
|---|---|---|---|---|---|
| **Resolve** | `POST /api/v1/agent/conversations/{id}/resolve`, agent | `status = RESOLVED`, `resolved_at`; assignment released through the allocation service (`RELEASE_PENDING`) | all messages and history | yes: reopen, or a contact message (auto-reopen, D10) | state event `RESOLVED`, `conversation.updated` |
| **Archive** | `POST …/archive`, agent; purge as `system:retention` | `archived_at`, `archived_by`, `archive_reason`; the conversation leaves the default inbox view (`archived=false`) and accepts no messages and no status change (409 `CONVERSATION_ARCHIVED`; the widget then starts a new conversation); an assignment in `REQUESTED`, `QUEUED`, `ASSIGNED`, `REOPEN_PENDING` or `FAILED` is released through the allocation service (`RELEASE_PENDING`), because an archived conversation holds no capacity (R-P13, R-P17, R-P31 M6) | status, all messages | yes: unarchive (not after purge) | state event `ARCHIVED` |
| **Unarchive** | `POST …/unarchive`, agent | archive marker cleared; an `OPEN` or `PENDING` conversation requests assignment again (`REOPEN_PENDING`, R-P13); 409 `CONVERSATION_ACTIVE_EXISTS` if it would give the contact a second active conversation (D11); 409 `INVALID_TRANSITION` if purged (R-P8) | everything | yes | state event `UNARCHIVED` |
| **Reopen** | `POST …/reopen`, agent; or a contact message into `RESOLVED` | `status = OPEN`; reassignment requested (`REOPEN_PENDING`); rejected while archived | prior messages and state events | yes: resolve again | state event `REOPENED` / `REOPENED_BY_CONTACT` |
| **Delete message** (hide) | none in the MVP (NEXT) | a future tombstone: `deleted_at`, `deleted_by`, `deletion_reason`, body removed, `sequence` kept (R2) | the row and its sequence | n/a | n/a |
| **Purge** | `POST /api/v1/admin/retention/purge`, operator, local-dev | hard-deletes messages, attachment rows, read cursors and outbox rows of eligible conversations; sets `purged_at`, archives, sets `RESOLVED` where the conversation was not (R-P25), clears the preview and the free-text archive and state-event reasons (D16, R-P31 M10) | conversation row, participants, state events | **no** | `purge_runs` row (R16) |

Archive never deletes data, and purge is never an unbounded synchronous statement (master spec 5.6). The full
transition table is in [domain-model.md](domain-model.md) section 5.

## 2. Retention policy and eligibility (D16)

- One policy per tenant in `retention_policies(tenant_id, resolved_retention_days > 0, legal_hold)`, set with
  `PUT /api/v1/admin/retention/policies/{tenantId}` `{resolvedRetentionDays, legalHold}`. The local seed creates 30 days
  for `tenant-local`. No policy → the purge answers 404 `NOT_FOUND` for that tenant.
- **Cutoff** = `now − resolved_retention_days` (the `Clock` bean). A request `cutoff` overrides it only when
  `app.local-dev.enabled=true`, for local verification (`RetentionIT.cutoffOverrideIgnoredWhenLocalDevDisabled`).
- **Eligible** = `tenant_id = :tenant AND purged_at IS NULL AND ((status = 'RESOLVED' AND resolved_at < :cutoff) OR
  (archived_at IS NOT NULL AND archived_at < :cutoff))`, and the tenant is not on legal hold (R-P25). That predicate is
  the whole rule: a resolved conversation need not be archived, an archived one need not be resolved (archived
  conversations follow the same retention clock as resolved ones; `RetentionIT.archivedOpenConversationIsPurgedAndResolved`),
  and a conversation that was resolved long ago but reopened and not archived is `OPEN` and therefore not eligible
  (`RetentionIT.reopenedConversationIsNotEligible`). Index: `ix_conversations_purge_due (tenant_id, id) WHERE purged_at
  IS NULL AND (status = 'RESOLVED' OR archived_at IS NOT NULL)`, which covers both branches; batches are ordered by `id`
  (UUID v7, so roughly by age), because `resolved_at` can be null for a conversation eligible only by its archive time.
- **Legal hold**: a run for a tenant on hold records a `COMPLETED` run with zero counts and `last_error = 'LEGAL_HOLD'`
  and touches nothing (`RetentionIT.legalHoldSkipsTenant`). The hold is re-checked before every batch, so a hold set
  while a run is going stops it before its next batch (`RetentionIT.legalHoldSetDuringRunStopsBeforeNextBatch`, R-P31
  M9). The hold is never bypassed silently.
- The purge is **not scheduled** in the MVP: an operator runs it. Data therefore grows until someone does.

## 3. The purge job (`RetentionService.run(PurgeRequest)`)

`PurgeRequest(tenantId, dryRun, cutoff, batchSize, maxBatches)`; the admin endpoint defaults `batchSize = 500`,
`maxBatches = 20`.

1. Load the policy (404 if none); stop with the legal-hold record if `legal_hold`.
2. Compute the cutoff; insert `purge_runs` with `status = RUNNING`.
3. Up to `maxBatches` times, each batch in **its own transaction** of at most `batchSize` conversations:
   1. Re-read the policy: if `legal_hold` has been set since the run started, stop before touching this batch; the run
      ends `COMPLETED` with the counts so far and `last_error = 'LEGAL_HOLD'` (R-P31 M9).
   2. `SELECT id FROM conversations WHERE <eligible> ORDER BY id LIMIT :batchSize FOR UPDATE SKIP LOCKED`.
      No rows → stop.
   3. Dry run: count the messages, attachments and cursors of those ids, accumulate, delete nothing, mark nothing. Since
      nothing is marked, the next dry-run batch pages by keyset `id > :lastId` without row locks.
   4. Otherwise delete, in this order: `message_attachments` of those conversations' messages; `messages WHERE
      conversation_id = ANY(:ids)`; `conversation_read_cursors` of those ids; `outbox_events WHERE tenant_id = :t AND
      aggregate_type = 'conversation' AND aggregate_id = ANY(:ids)` (their payloads embed message bodies). Then
      `UPDATE conversations SET purged_at = :now, archived_at = COALESCE(archived_at, :now), archived_by =
      COALESCE(archived_by, 'system:retention'), archive_reason = NULL, status = 'RESOLVED', resolved_at =
      COALESCE(resolved_at, :now), last_message_preview = NULL, version = version + 1, updated_at = :now` (the status
      and `resolved_at` change only rows eligible by their archive time alone, so "purged ⇒ `RESOLVED` ∧ archived"
      holds, R-P25), and `UPDATE conversation_state_events SET details = details - 'reason' WHERE tenant_id = :t AND
      conversation_id = ANY(:ids)`: archive and state-event reasons are free text that may hold personal data (R-P31
      M10). The purge writes no state event (`purge_runs` is its audit) and no outbox row (R-P25).
   5. Update the run row's counters and `batches` after every batch.
4. Mark the run `COMPLETED` with `finished_at`. On an exception: mark it `FAILED` with the exception class name in
   `last_error` and rethrow; earlier batches stay committed, and a rerun picks up the remaining rows without counting
   the purged ones again (`RetentionIT.failedBatchIsRecordedAndRerunCompletes`).
5. Reaching `maxBatches` with rows left is a normal `COMPLETED` run with `batches = maxBatches`; the operator reruns
   (`RetentionIT.purgeStopsAtMaxBatches`).

`FOR UPDATE SKIP LOCKED` is appropriate here because batches are independent worker claims (master spec 8.4); a
conversation locked by a concurrent command is skipped and caught by a later run. Purge emits no realtime event (R-P9,
R-P25): the conversation is already resolved or archived (and so out of the default views), and a client that still
shows it sees the purged state at its next reconcile or `version` jump (R3). A purged conversation's view carries
`purgedAt` and `unreadCount = 0` (R-P31 M5).

## 4. Run record (`purge_runs`, `PurgeRunView`)

| Field | Meaning |
|---|---|
| `id`, `tenant_id`, `dry_run`, `cutoff` | what was asked |
| `status` | `RUNNING`, `COMPLETED`, `FAILED` |
| `conversations_examined` | eligible conversations found (dry run) or purged |
| `messages_deleted`, `attachments_deleted`, `cursors_deleted` | rows deleted, or "would delete" in a dry run |
| `batches` | batches completed |
| `last_error` | `LEGAL_HOLD` (hold set before or during the run, R-P31 M9), or the exception class name of a failure; never row content |
| `started_at`, `finished_at` | timing |

`GET /api/v1/admin/retention/runs/{id}` returns it. Counter `retention.purge.conversations{result=purged|dry_run}`.

## 5. What survives a purge, and privacy caveats

Survives: the `conversations` row (ids, tenant, inbox, contact id, status `RESOLVED`, timestamps, `last_sequence`,
assignment projection, archive and purge markers; `archive_reason` is cleared), `conversation_participants`,
`conversation_state_events` (actors, statuses, `details` without message text and without the free-text `reason`,
R-P31 M10), the `contacts` row (`external_id`, `display_name`), `purge_runs`, and the
allocation service's own conversation and assignment records (ids, timestamps, agent ids; never message text).

Caveats (no regulatory compliance is claimed; master spec 16):
- Purge removes message content, not people: contact records and participant rows remain. Erasing a contact is NEXT.
- Centrifugo may hold purged events in channel history for up to `history_ttl` (300 s) after their publication.
- Database backups and their retention are outside the application; Plan 05 T4 documents the local backup/restore
  procedure, which backs up and restores the platform and allocation databases together, so the assignment projection
  and the engine's records stay consistent (R-P31 M18).
- Logs never contain message bodies (Plan 05 `LogHygieneTest`), so purge has nothing to remove there.
- Attachments: no object storage exists (D15), so there are no files to orphan; the NEXT design must delete objects in
  the same bounded batches with retries.
- `last_sequence` is kept, so a purged conversation's head no longer matches its (absent) messages; Plan 07
  `Invariants.assertAll` excludes purged conversations from that check.

## 6. Operator procedure (local-dev)

All calls need `X-Admin-Token: $APP_LOCAL_ADMIN_TOKEN` and `app.local-dev.enabled=true`.

1. Set or confirm the policy:
   `PUT /api/v1/admin/retention/policies/tenant-local` `{"resolvedRetentionDays": 30, "legalHold": false}`.
2. Dry run: `POST /api/v1/admin/retention/purge` `{"tenantId": "tenant-local", "dryRun": true}` (add `"cutoff"` for
   local verification only). Read the counts in the returned `PurgeRunView`.
3. Check the counts against expectations (for example with `SELECT count(*)` on the eligible predicate). Stop if they
   are unexpected or the tenant should be on hold.
4. Execute with the same parameters and `"dryRun": false`.
5. `GET /api/v1/admin/retention/runs/{id}` until `status` is `COMPLETED`; if `FAILED`, read `last_error`, fix the
   cause and rerun step 4 (already purged rows are skipped).
6. If `batches = maxBatches`, rerun step 4 until a run examines 0 conversations.

Plan 05 T2 adds `curl` examples to `docs/runbooks/local-stack.md`; Plan 06's E2E scenario
(`visitorAndAgentFullLifecycle`, step 12) runs this procedure against the live stack.

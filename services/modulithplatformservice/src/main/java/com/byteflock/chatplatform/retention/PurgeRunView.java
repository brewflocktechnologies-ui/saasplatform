package com.byteflock.chatplatform.retention;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One purge run (dry runs included) as stored in {@code purge_runs} and returned by the admin API. */
public record PurgeRunView(UUID id, String tenantId, boolean dryRun, OffsetDateTime cutoff, String status,
		int conversationsExamined, int messagesDeleted, int attachmentsDeleted, int cursorsDeleted, int batches,
		String lastError, OffsetDateTime startedAt, OffsetDateTime finishedAt) {
}

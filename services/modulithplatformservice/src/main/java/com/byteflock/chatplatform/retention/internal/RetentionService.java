package com.byteflock.chatplatform.retention.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.byteflock.chatplatform.retention.PurgeRequest;
import com.byteflock.chatplatform.retention.PurgeRunView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual, bounded retention purge (D16). Eligibility: not purged and (resolved before the cutoff, or archived before the
 * cutoff) — both branches of R-P25. The legal hold is re-checked before every batch (R-P31 M9); a dry run counts without
 * writing; the run row records counts and status either way.
 */
@Service
public class RetentionService {

	private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

	private static final int DEFAULT_RETENTION_DAYS = 30;

	private final RetentionRepository repository;

	private final TransactionTemplate tx;

	private final Clock clock;

	RetentionService(RetentionRepository repository, TransactionTemplate tx, Clock clock) {
		this.repository = repository;
		this.tx = tx;
		this.clock = clock;
	}

	public PurgeRunView run(PurgeRequest request) {
		if (request.tenantId() == null || request.tenantId().isBlank()) {
			throw new IllegalArgumentException("tenantId is required");
		}
		String tenantId = request.tenantId();
		OffsetDateTime now = OffsetDateTime.now(this.clock);
		OffsetDateTime cutoff = (request.cutoff() != null) ? request.cutoff()
				: now.minusDays(this.repository.retentionDays(tenantId).orElse(DEFAULT_RETENTION_DAYS));
		UUID runId = this.tx.execute((status) -> this.repository.startRun(tenantId, request.dryRun(), cutoff, now));
		Counter counter = new Counter();
		String status = "COMPLETED";
		String error = null;
		try {
			if (!this.repository.legalHold(tenantId)) {
				for (int batch = 0; batch < request.maxBatches(); batch++) {
					// The hold is re-checked before every batch (R-P31 M9): it may be set mid-run.
					if (this.repository.legalHold(tenantId)) {
						break;
					}
					List<UUID> eligible = this.repository.eligible(tenantId, cutoff, request.batchSize());
					if (eligible.isEmpty()) {
						break;
					}
					boolean lastBatch = eligible.size() < request.batchSize();
					this.tx.executeWithoutResult((txStatus) -> {
						for (UUID conversationId : eligible) {
							int messages = this.repository.countMessages(conversationId);
							int attachments = this.repository.countAttachments(conversationId);
							int cursors = this.repository.countCursors(conversationId);
							if (!request.dryRun()) {
								this.repository.purgeConversation(tenantId, conversationId,
										OffsetDateTime.now(this.clock));
							}
							counter.add(messages, attachments, cursors);
						}
					});
					counter.batches++;
					if (lastBatch) {
						break;
					}
				}
			}
		}
		catch (RuntimeException ex) {
			log.warn("Retention purge failed", ex);
			status = "FAILED";
			error = ex.getClass().getSimpleName();
		}
		this.repository.finishRun(runId, status, counter.conversations, counter.messages, counter.attachments,
				counter.cursors, counter.batches, error, OffsetDateTime.now(this.clock));
		return this.repository.findRun(runId).orElseThrow(() -> new IllegalStateException("purge run vanished"));
	}

	public Optional<PurgeRunView> find(UUID runId) {
		return this.repository.findRun(runId);
	}

	/** Sets or replaces a tenant's policy; used by the admin API and local verification. */
	public void updatePolicy(String tenantId, int resolvedRetentionDays, boolean legalHold) {
		if (tenantId == null || tenantId.isBlank()) {
			throw new IllegalArgumentException("tenantId is required");
		}
		if (resolvedRetentionDays <= 0) {
			throw new IllegalArgumentException("resolvedRetentionDays must be positive");
		}
		this.repository.upsertPolicy(tenantId, resolvedRetentionDays, legalHold, OffsetDateTime.now(this.clock));
	}

	private static final class Counter {

		private int conversations;

		private int messages;

		private int attachments;

		private int cursors;

		private int batches;

		private void add(int messages, int attachments, int cursors) {
			this.conversations++;
			this.messages += messages;
			this.attachments += attachments;
			this.cursors += cursors;
		}

	}

}

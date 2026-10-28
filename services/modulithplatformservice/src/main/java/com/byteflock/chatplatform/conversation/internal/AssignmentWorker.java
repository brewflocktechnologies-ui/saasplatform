package com.byteflock.chatplatform.conversation.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claims due assignment rows in a short transaction, then lets {@link AssignmentOrchestrator} call the engine outside any
 * transaction. The per-row lease rule (R-P28) stops the batch before a call that could outlive the claim; the rest is
 * reclaimed after the lease. The bean always exists and the tick returns immediately while
 * {@code app.assignment.enabled=false}; tests call {@link #runOnce()}.
 */
@Component
public class AssignmentWorker {

	private static final Logger log = LoggerFactory.getLogger(AssignmentWorker.class);

	private final AssignmentOrchestrator orchestrator;

	private final ConversationRepository conversations;

	private final AssignmentProperties properties;

	private final TransactionTemplate tx;

	private final Clock clock;

	private final String workerId = "assignment-" + UUID.randomUUID().toString().substring(0, 8);

	AssignmentWorker(AssignmentOrchestrator orchestrator, ConversationRepository conversations,
			AssignmentProperties properties, TransactionTemplate tx, Clock clock) {
		this.orchestrator = orchestrator;
		this.conversations = conversations;
		this.properties = properties;
		this.tx = tx;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${app.assignment.poll-delay}")
	public void tick() {
		if (!this.properties.enabled()) {
			return;
		}
		try {
			runOnce();
		}
		catch (RuntimeException ex) {
			log.warn("Assignment tick failed: {}", ex.getClass().getSimpleName());
		}
	}

	/** Processes at most one claimed batch; returns how many rows were handed to the orchestrator. */
	public int runOnce() {
		OffsetDateTime now = OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC);
		List<ClaimedAssignment> batch = this.tx.execute((status) -> this.conversations
			.claimDueAssignments(this.workerId, this.properties.batchSize(), now, this.properties.lease()));
		if (batch == null || batch.isEmpty()) {
			return 0;
		}
		int processed = 0;
		for (ClaimedAssignment row : batch) {
			if (this.clock.instant().plus(AssignmentOrchestrator.ALLOCATION_TIMEOUT).isAfter(row.leaseEnd().toInstant())) {
				log.debug("Assignment batch stopped before conversation={}: lease would expire", row.id());
				break;
			}
			this.orchestrator.process(row);
			processed++;
		}
		return processed;
	}

}

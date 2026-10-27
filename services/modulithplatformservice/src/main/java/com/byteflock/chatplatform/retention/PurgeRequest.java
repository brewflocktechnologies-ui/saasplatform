package com.byteflock.chatplatform.retention;

import java.time.OffsetDateTime;

/**
 * One retention purge request (Plan 05). A dry run reports the same counts and changes nothing; {@code cutoff} is
 * normally {@code now − resolved_retention_days} but local-dev verification may pass it explicitly.
 */
public record PurgeRequest(String tenantId, boolean dryRun, OffsetDateTime cutoff, Integer batchSize,
		Integer maxBatches) {

	public PurgeRequest {
		batchSize = (batchSize != null && batchSize > 0) ? Math.min(batchSize, 500) : 50;
		maxBatches = (maxBatches != null && maxBatches > 0) ? Math.min(maxBatches, 1000) : 100;
	}

}

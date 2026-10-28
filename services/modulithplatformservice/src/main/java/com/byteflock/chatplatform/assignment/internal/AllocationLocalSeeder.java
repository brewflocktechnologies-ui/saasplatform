package com.byteflock.chatplatform.assignment.internal;

import java.time.Duration;
import java.util.List;

import com.byteflock.chatplatform.assignment.AllocationRejectedException;
import com.byteflock.chatplatform.assignment.AllocationSeeder;
import com.byteflock.chatplatform.assignment.AllocationUnavailableException;
import com.byteflock.chatplatform.assignment.SeedAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds the allocation side of the local tenant without blocking startup: the allocation service may come up after this
 * backend, so a virtual thread retries every 10 s for up to 5 minutes and logs each attempt. LOCAL POC — the seeded
 * identities are demo identities, not real accounts.
 */
@Component
@Order(20)
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
class AllocationLocalSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AllocationLocalSeeder.class);

	private static final Duration RETRY_INTERVAL = Duration.ofSeconds(10);

	private static final Duration DEADLINE = Duration.ofMinutes(5);

	private final AllocationSeeder seeder;

	private final String tenantId;

	private final String queueId;

	AllocationLocalSeeder(AllocationSeeder seeder, @Value("${app.seed.tenant-id}") String tenantId,
			@Value("${app.seed.queue-id}") String queueId) {
		this.seeder = seeder;
		this.tenantId = tenantId;
		this.queueId = queueId;
	}

	@Override
	public void run(ApplicationArguments args) {
		Thread.ofVirtual().name("allocation-seeder").start(this::seedWithRetry);
	}

	private void seedWithRetry() {
		long deadline = System.nanoTime() + DEADLINE.toNanos();
		while (System.nanoTime() < deadline) {
			try {
				this.seeder.ensureTenant(this.tenantId, "Local tenant", this.queueId,
						List.of(new SeedAgent("agentbob", "Agent Bob", 3)));
				log.info("Allocation service seeded for tenant {} (LOCAL POC — NOT REAL AUTHENTICATION)", this.tenantId);
				return;
			}
			catch (AllocationUnavailableException ex) {
				log.info("Allocation service not reachable yet, retrying in {} s", RETRY_INTERVAL.toSeconds());
			}
			catch (AllocationRejectedException ex) {
				log.warn("Allocation seeding rejected with HTTP {} {}, retrying in {} s", ex.status(), ex.code(),
						RETRY_INTERVAL.toSeconds());
			}
			try {
				Thread.sleep(RETRY_INTERVAL.toMillis());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		log.warn("Allocation seeding gave up after {} minutes; assignment flows will keep retrying in the worker",
				DEADLINE.toMinutes());
	}

}

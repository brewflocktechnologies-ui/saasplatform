package com.byteflock.chatplatform.tenancy.internal;

import com.byteflock.chatplatform.tenancy.TenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Seeds the local tenant on startup ({@code app.seed.enabled=true}, local profile only): tenant, one inbox with the
 * configured widget key, agent {@code agentbob}, contact {@code userone} and a 30-day retention policy.
 * <p>
 * Every step is an idempotent upsert, so restarting the app never duplicates rows and never changes the widget key.
 * LOCAL POC — NOT REAL AUTHENTICATION: these fixed identities are the local demo identities, not real accounts.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class LocalSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LocalSeeder.class);

	private final TenantDirectory directory;

	private final JdbcClient jdbc;

	private final String tenantId;

	private final String widgetKey;

	private final String queueId;

	public LocalSeeder(TenantDirectory directory, JdbcClient jdbc, @Value("${app.seed.tenant-id}") String tenantId,
			@Value("${app.seed.widget-key}") String widgetKey, @Value("${app.seed.queue-id}") String queueId) {
		this.directory = directory;
		this.jdbc = jdbc;
		this.tenantId = tenantId;
		this.widgetKey = widgetKey;
		this.queueId = queueId;
	}

	@Override
	public void run(ApplicationArguments args) {
		this.directory.ensureTenant(this.tenantId, "Local tenant");
		this.directory.ensureInbox(this.tenantId, "Website", this.widgetKey, this.queueId);
		this.directory.ensureAgent(this.tenantId, "agentbob", "Agent Bob");
		this.directory.ensureContact(this.tenantId, "userone", "User One");
		// Seed-only retention policy; the retention module (Plan 05) owns this table at runtime.
		this.jdbc.sql("""
				INSERT INTO retention_policies (tenant_id, resolved_retention_days) VALUES (:tenantId, 30)
				ON CONFLICT (tenant_id) DO NOTHING
				""").param("tenantId", this.tenantId).update();
		log.info("Seeded local tenant {} with inbox widget key {} and agent agentbob (LOCAL POC — NOT REAL AUTHENTICATION)",
				this.tenantId, this.widgetKey);
	}

}

package com.byteflock.chatplatform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for every integration test that talks to real PostgreSQL (the real {@code V1…V3} SQL) and the in-process
 * Centrifugo stub.
 * <p>
 * The container is started once per JVM from a static initializer on first use and is deliberately <em>not</em> a
 * {@code @Container} field: the JUnit extension would stop it after every test class while Spring's cached application
 * context keeps pointing at the old port. Flyway runs once, on the same database. {@link #cleanDatabase()} truncates
 * every V3 table before each test because several assertions are global counts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
public abstract class PlatformIntegrationTest {

	private static final String POSTGRES_IMAGE = "postgres:18.6-alpine3.24";

	/** Test-only secret; integration tests must never depend on a developer's {@code .env}. */
	public static final String SESSION_SECRET = "test-session-secret-0123456789abcdef";

	public static final String ADMIN_TOKEN = "test-admin-token";

	private static final PostgreSQLContainer POSTGRES = startPostgres();

	/** One stub for the whole JVM; {@link #cleanDatabase()} clears its recorded requests. */
	protected static final CentrifugoStub CENTRIFUGO = new CentrifugoStub();

	@Autowired
	protected MockMvcTester mvc;

	@Autowired
	protected JdbcClient jdbc;

	protected final JsonMapper json = JsonMapper.builder().build();

	private static PostgreSQLContainer startPostgres() {
		PostgreSQLContainer container = new PostgreSQLContainer(POSTGRES_IMAGE);
		container.start();
		Flyway.configure()
			.dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
			.schemas("platform")
			.locations("filesystem:" + System.getProperty("flyway.sql.dir", "../../database/flywayMigration/sql"))
			.load()
			.migrate();
		return container;
	}

	@DynamicPropertySource
	static void platformProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=platform");
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("app.centrifugo.url", CENTRIFUGO::url);
		registry.add("app.sessions.secret", () -> SESSION_SECRET);
	}

	/**
	 * Fresh data for every test. Truncating one statement keeps every foreign key path consistent; the listed order is
	 * irrelevant because all V3 tables are truncated together.
	 */
	@BeforeEach
	void cleanDatabase() {
		this.jdbc.sql("""
				TRUNCATE TABLE platform.conversation_state_events, platform.conversation_read_cursors,
				               platform.message_attachments, platform.messages, platform.conversation_participants,
				               platform.outbox_events, platform.purge_runs, platform.retention_policies,
				               platform.conversations, platform.agents, platform.contacts, platform.inboxes,
				               platform.tenants
				""").update();
		CENTRIFUGO.requests.clear();
		CENTRIFUGO.reset();
	}

	protected JsonNode body(MvcTestResult result) {
		return this.json.readTree(result.getResponse().getContentAsByteArray());
	}

	/** Inserts a tenant so a test can seed tenant-owned rows without the seeder. */
	protected void insertTenant(String tenantId) {
		this.jdbc.sql("INSERT INTO tenants (id, name) VALUES (:id, :name)")
			.param("id", tenantId)
			.param("name", "Tenant " + tenantId)
			.update();
	}

}

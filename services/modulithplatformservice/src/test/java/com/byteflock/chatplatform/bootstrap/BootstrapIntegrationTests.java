package com.byteflock.chatplatform.bootstrap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: disposable PostgreSQL (Testcontainers) + the real migrations from database/flywayMigration/sql,
 * applied by Flyway in the test (never by the application). Skipped automatically when Docker is unavailable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BootstrapIntegrationTests {

	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine3.24");

	@BeforeAll
	static void migrate() {
		Flyway.configure()
			.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
			.schemas("platform")
			.locations("filesystem:" + System.getProperty("flyway.sql.dir", "../../database/flywayMigration/sql"))
			.load()
			.migrate();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=platform");
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
	}

	@Autowired
	MockMvcTester mvc;

	@Test
	void bootstrapEndpointReturnsTheFlywaySeedRecord() {
		assertThat(this.mvc.get().uri("/api/v1/bootstrap")).hasStatusOk()
			.bodyJson()
			.satisfies((json) -> {
				assertThat(json).extractingPath("$.setupKey").isEqualTo("local-poc");
				assertThat(json).extractingPath("$.id").isEqualTo(1);
			});
	}

	@Test
	void statusReportsDatabaseUpWithSchemaVersion() {
		assertThat(this.mvc.get().uri("/api/v1/status")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.dependencies.database.detail")
			.asString()
			.matches("schema version \\d+");
	}

}

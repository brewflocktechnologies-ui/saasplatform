package com.byteflock.chatplatform.bootstrap;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class BootstrapRepository {

	private final JdbcClient jdbc;

	BootstrapRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<BootstrapRecord> findLatest() {
		return this.jdbc
			.sql("SELECT id, setup_key, message, created_at FROM platform_bootstrap ORDER BY id DESC LIMIT 1")
			.query((rs, n) -> new BootstrapRecord(rs.getLong("id"), rs.getString("setup_key"), rs.getString("message"),
					rs.getObject("created_at", OffsetDateTime.class)))
			.optional();
	}

}

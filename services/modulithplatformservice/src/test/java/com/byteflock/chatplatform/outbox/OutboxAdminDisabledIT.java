package com.byteflock.chatplatform.outbox;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** With {@code app.local-dev.enabled=false} the admin API does not exist at all. */
@ActiveProfiles("it-disabled")
class OutboxAdminDisabledIT extends PlatformIntegrationTest {

	@Test
	void adminRoutesAbsentWhenLocalDevDisabled() {
		assertThat(this.mvc.get().uri("/api/v1/admin/outbox").header("X-Admin-Token", ADMIN_TOKEN).exchange())
			.hasStatus(404);
	}

}

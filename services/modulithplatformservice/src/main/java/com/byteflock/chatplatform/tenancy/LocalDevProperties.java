package com.byteflock.chatplatform.tenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local-development switches ({@code app.local-dev.*}).
 * <p>
 * LOCAL POC — NOT REAL AUTHENTICATION: when {@code enabled} is true the agent local-login route and the admin API are
 * served; {@code adminToken} is compared in constant time with the {@code X-Admin-Token} header. A blank token rejects
 * every admin request.
 */
@ConfigurationProperties("app.local-dev")
public record LocalDevProperties(boolean enabled, String adminToken) {

	public LocalDevProperties {
		adminToken = (adminToken != null) ? adminToken : "";
	}

}

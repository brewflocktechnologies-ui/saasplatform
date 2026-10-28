/**
 * Tenancy: tenants, inboxes, contacts, the agent roster and local identity.
 * <p>
 * One bearer mechanism for both principals (ADR-0008): HS256 session JWTs signed with
 * {@code APP_SESSION_TOKEN_HMAC_SECRET_KEY}, verified by {@link com.byteflock.chatplatform.tenancy.SessionTokens}
 * and turned into a {@link com.byteflock.chatplatform.tenancy.Caller} by the web layer. Tenant ids come only from a
 * verified token (or the local-dev admin token) and every data statement filters on them.
 * <p>
 * LOCAL POC — NOT REAL AUTHENTICATION: {@code POST /api/v1/agent/local-login} and the {@code X-Admin-Token} admin API
 * are local development shortcuts, disabled unless {@code app.local-dev.enabled=true}.
 */
package com.byteflock.chatplatform.tenancy;

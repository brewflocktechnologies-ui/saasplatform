/**
 * Integration with the external allocation service (ADR-0009): a typed HTTP gateway, a local seeder and the agent
 * presence view. The allocation service owns assignment records, capacity and availability; this module only calls it and
 * maps its answers — the platform never computes capacity itself.
 * <p>
 * Errors are classified for the orchestrator: {@link com.byteflock.chatplatform.assignment.AllocationUnavailableException}
 * (connection, timeout, 5xx) is transient and retried; {@link com.byteflock.chatplatform.assignment.AllocationRejectedException}
 * carries the engine's HTTP status and code, and the caller decides whether that is retryable, terminal or "already done".
 */
package com.byteflock.chatplatform.assignment;

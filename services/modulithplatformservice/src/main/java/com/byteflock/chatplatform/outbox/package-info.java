/**
 * Transactional outbox: one row per durable domain event, written in the same transaction as the change (D6, ADR-0007).
 * The publisher worker (Plan 03) claims due rows and broadcasts them to Centrifugo at least once; clients dedupe by
 * {@code eventId}.
 */
package com.byteflock.chatplatform.outbox;

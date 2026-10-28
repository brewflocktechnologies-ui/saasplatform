package com.byteflock.chatplatform.outbox;

/** Delivery state of an outbox row. */
public enum OutboxStatus {

	PENDING,

	PUBLISHED,

	PARKED

}

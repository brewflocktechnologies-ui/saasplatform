package com.byteflock.chatplatform.outbox.internal;

import org.springframework.stereotype.Component;

/**
 * Test seam of the publisher: runs between the Centrifugo call and the status mark, so tests can simulate a worker that
 * publishes and then crashes before marking (the event must be republished later — at-least-once).
 */
@Component
public class OutboxHook {

	private volatile Runnable afterPublishBeforeMark = () -> {
	};

	public void setAfterPublishBeforeMark(Runnable hook) {
		this.afterPublishBeforeMark = (hook != null) ? hook : () -> {
		};
	}

	public void reset() {
		this.afterPublishBeforeMark = () -> {
		};
	}

	void afterPublish() {
		this.afterPublishBeforeMark.run();
	}

}

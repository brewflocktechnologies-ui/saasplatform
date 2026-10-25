/**
 * Centrifugo integration: server-side publication over the Centrifugo HTTP API and short-lived connection /
 * subscription JWTs. Secrets stay in this module; browsers only ever receive signed, expiring tokens.
 * Channel authorization decisions belong to the calling module (e.g. {@code chat}).
 */
package com.byteflock.chatplatform.realtime;

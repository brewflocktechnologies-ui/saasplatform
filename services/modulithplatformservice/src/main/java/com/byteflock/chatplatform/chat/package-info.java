/**
 * Chat MVP (specs/mvpsetup.spec.md): one demo conversation between visitor {@code userone} and agent
 * {@code agentbob}, persisted in PostgreSQL and delivered live through Centrifugo after commit.
 * <p>
 * LOCAL POC — NOT REAL AUTHENTICATION: actors are identified by a client-supplied {@code actorId} restricted to two
 * hardcoded values ({@link com.byteflock.chatplatform.chat.DemoActor}). Replace with real identity before any shared
 * environment.
 */
package com.byteflock.chatplatform.chat;

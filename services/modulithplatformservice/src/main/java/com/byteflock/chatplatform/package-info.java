/**
 * Chat platform modular monolith. Each direct sub-package is a Spring Modulith application module:
 * {@code bootstrap} (setup proof), {@code health} (status reporting) and {@code shared} (cross-cutting web/telemetry
 * primitives). Product modules (tenancy, conversations, ...) are added by feature specs.
 */
package com.byteflock.chatplatform;

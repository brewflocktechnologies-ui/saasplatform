package com.byteflock.chatplatform.bootstrap;

import java.time.OffsetDateTime;

public record BootstrapRecord(long id, String setupKey, String message, OffsetDateTime createdAt) {
}

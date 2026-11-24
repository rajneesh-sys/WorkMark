package com.workmark.auth.service;

import java.time.Instant;

public interface AccountTokenDelivery {
    String deliver(String recipient, String displayName, String token, Instant expiresAt, Purpose purpose);
    default void deliver(String recipient, String displayName, String token, Instant expiresAt, Purpose purpose,
                         String messageId) {
        deliver(recipient, displayName, token, expiresAt, purpose);
    }
    default boolean exposesDevelopmentToken() { return false; }

    enum Purpose {
        PASSWORD_SETUP,
        PASSWORD_RESET
    }
}

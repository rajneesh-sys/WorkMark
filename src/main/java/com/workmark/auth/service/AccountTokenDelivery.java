package com.workmark.auth.service;

import java.time.Instant;

public interface AccountTokenDelivery {
    String deliver(String recipient, String displayName, String token, Instant expiresAt, Purpose purpose);

    enum Purpose {
        PASSWORD_SETUP,
        PASSWORD_RESET
    }
}

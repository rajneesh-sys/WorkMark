package com.workmark.auth.service;

import java.time.Instant;

public interface AccountTokenOutbox {
    void enqueue(String recipient, String displayName, String token, Instant expiresAt,
                 AccountTokenDelivery.Purpose purpose);
}

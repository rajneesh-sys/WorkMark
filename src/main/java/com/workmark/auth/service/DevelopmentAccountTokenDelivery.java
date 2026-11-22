package com.workmark.auth.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Profile({"dev", "test"})
public class DevelopmentAccountTokenDelivery implements AccountTokenDelivery {
    @Override
    public String deliver(String recipient, String displayName, String token, Instant expiresAt, Purpose purpose) {
        return token;
    }
}

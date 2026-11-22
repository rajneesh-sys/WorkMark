package com.workmark.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class ActivationTokenService {
    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;
    private final Duration timeToLive;

    public ActivationTokenService(Clock clock,
                                  @Value("${app.auth.activation-token-ttl:PT24H}") Duration timeToLive) {
        if (timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("Account activation token TTL must be positive");
        }
        this.clock = clock;
        this.timeToLive = timeToLive;
    }

    public IssuedToken issue() {
        return issue(timeToLive);
    }

    public IssuedToken issue(Duration lifetime) {
        if (lifetime == null || lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("Account token lifetime must be positive");
        }
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        return new IssuedToken(rawToken, hash(rawToken), clock.instant().plus(lifetime));
    }

    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record IssuedToken(String value, String hash, Instant expiresAt) {}
}

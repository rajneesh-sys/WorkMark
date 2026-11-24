package com.workmark.auth.service;

public interface PasswordResetRateLimiter {
    boolean isAllowed(String remoteAddress, String email);
    void recordRequest(String remoteAddress, String email);
    void recordSuccess(String email);
}

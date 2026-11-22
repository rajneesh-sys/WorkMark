package com.workmark.auth.service;

public interface LoginRateLimiter {
    boolean isAllowed(String remoteAddress, String email);

    void recordFailure(String remoteAddress, String email);

    void recordSuccess(String email);
}

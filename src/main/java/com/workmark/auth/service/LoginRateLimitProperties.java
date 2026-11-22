package com.workmark.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.auth.login-rate-limit")
public class LoginRateLimitProperties {
    private int maxFailures = 5;
    private Duration window = Duration.ofMinutes(5);
    private Duration baseBlock = Duration.ofSeconds(30);
    private Duration maxBlock = Duration.ofMinutes(15);
    private int maxTrackedKeys = 100_000;

    public int getMaxFailures() { return maxFailures; }
    public void setMaxFailures(int maxFailures) { this.maxFailures = maxFailures; }
    public Duration getWindow() { return window; }
    public void setWindow(Duration window) { this.window = window; }
    public Duration getBaseBlock() { return baseBlock; }
    public void setBaseBlock(Duration baseBlock) { this.baseBlock = baseBlock; }
    public Duration getMaxBlock() { return maxBlock; }
    public void setMaxBlock(Duration maxBlock) { this.maxBlock = maxBlock; }
    public int getMaxTrackedKeys() { return maxTrackedKeys; }
    public void setMaxTrackedKeys(int maxTrackedKeys) { this.maxTrackedKeys = maxTrackedKeys; }

    public void validate() {
        if (maxFailures < 1 || maxTrackedKeys < 2 || window == null || window.isNegative()
                || window.isZero() || baseBlock == null || baseBlock.isNegative() || baseBlock.isZero()
                || maxBlock == null || maxBlock.compareTo(baseBlock) < 0) {
            throw new IllegalStateException("Login rate-limit configuration is invalid");
        }
    }
}

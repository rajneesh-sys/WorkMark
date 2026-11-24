package com.workmark.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.auth.login-rate-limit")
public class LoginRateLimitProperties {
    private int maxFailures = 5;
    private Duration window = Duration.ofMinutes(5);
    private Duration baseBlock = Duration.ofSeconds(30);
    private Duration maxBlock = Duration.ofMinutes(15);
    private int maxTrackedKeys = 100_000;
    private int ipMaxFailures = 20;
    private int resetMaxFailures = 3;
    private int resetIpMaxFailures = 10;
    private int resetMaxTrackedKeys = 100_000;
    private List<String> trustedProxies = new ArrayList<>();

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
    public int getIpMaxFailures() { return ipMaxFailures; }
    public void setIpMaxFailures(int ipMaxFailures) { this.ipMaxFailures = ipMaxFailures; }
    public int getResetMaxFailures() { return resetMaxFailures; }
    public void setResetMaxFailures(int resetMaxFailures) { this.resetMaxFailures = resetMaxFailures; }
    public int getResetIpMaxFailures() { return resetIpMaxFailures; }
    public void setResetIpMaxFailures(int resetIpMaxFailures) { this.resetIpMaxFailures = resetIpMaxFailures; }
    public int getResetMaxTrackedKeys() { return resetMaxTrackedKeys; }
    public void setResetMaxTrackedKeys(int resetMaxTrackedKeys) { this.resetMaxTrackedKeys = resetMaxTrackedKeys; }
    public List<String> getTrustedProxies() { return trustedProxies; }
    public void setTrustedProxies(List<String> trustedProxies) { this.trustedProxies = trustedProxies; }

    public void validate() {
        if (maxFailures < 1 || ipMaxFailures < 1 || resetMaxFailures < 1 || resetIpMaxFailures < 1
                || resetMaxTrackedKeys < 2 || maxTrackedKeys < 2 || window == null || window.isNegative()
                || window.isZero() || baseBlock == null || baseBlock.isNegative() || baseBlock.isZero()
                || maxBlock == null || maxBlock.compareTo(baseBlock) < 0) {
            throw new IllegalStateException("Login rate-limit configuration is invalid");
        }
    }
}

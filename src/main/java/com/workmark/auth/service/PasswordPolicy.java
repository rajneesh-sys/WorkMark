package com.workmark.auth.service;

import com.workmark.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

@Component
public class PasswordPolicy {
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password123", "password1234", "qwerty123456", "letmein12345",
            "welcome12345", "admin123456", "123456789012", "1234567890", "iloveyou123");
    private static final String ASCENDING = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String DESCENDING = new StringBuilder(ASCENDING).reverse().toString();

    public void validate(String password) {
        int length = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (password == null || password.length() < 12 || length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Password must be at least 12 characters and no more than 72 UTF-8 bytes");
        }
        String normalized = password.toLowerCase(Locale.ROOT);
        if (isCommon(normalized) || isRepetitive(password) || hasSequence(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Password is too common or predictable");
        }
    }

    private boolean isCommon(String password) {
        return COMMON_PASSWORDS.contains(password) || password.startsWith("password")
                || password.startsWith("qwerty") || password.startsWith("letmein");
    }

    private boolean isRepetitive(String password) {
        int[] points = password.codePoints().toArray();
        boolean identical = true;
        for (int i = 1; i < points.length; i++) {
            if (points[i] != points[0]) {
                identical = false;
                break;
            }
        }
        if (identical) return true;

        for (int patternLength = 1; patternLength <= 3; patternLength++) {
            if (points.length % patternLength != 0 || points.length / patternLength < 4) continue;
            boolean repeated = true;
            for (int i = patternLength; i < points.length; i++) {
                if (points[i] != points[i % patternLength]) {
                    repeated = false;
                    break;
                }
            }
            if (repeated) return true;
        }
        return false;
    }

    private boolean hasSequence(String password) {
        for (int i = 0; i <= password.length() - 6; i++) {
            String candidate = password.substring(i, i + 6);
            if (ASCENDING.contains(candidate) || DESCENDING.contains(candidate)) return true;
        }
        return false;
    }
}

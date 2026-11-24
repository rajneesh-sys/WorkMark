package com.workmark.auth.service;

import com.workmark.common.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void rejectsCommonRepeatedAndSequentialPasswords() {
        assertThrows(ApiException.class, () -> policy.validate("Password123456"));
        assertThrows(ApiException.class, () -> policy.validate("aaaaaaaaaaaa"));
        assertThrows(ApiException.class, () -> policy.validate("Abcdefgh123!"));
    }

    @Test
    void acceptsNonPredictablePasswordsWithinUtf8Bounds() {
        assertDoesNotThrow(() -> policy.validate("M0untain!River7"));
        assertThrows(ApiException.class, () -> policy.validate("é".repeat(37)));
    }
}

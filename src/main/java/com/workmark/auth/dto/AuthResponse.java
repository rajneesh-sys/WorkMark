package com.workmark.auth.dto;

import com.workmark.auth.entity.UserRole;

import java.util.UUID;

public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserView user) {
    public record UserView(UUID id, UUID companyId, UUID employeeId, String email, UserRole role) {}
}

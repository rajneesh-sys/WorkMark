package com.workmark.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountInvitationResponse(AuthResponse.UserView user, String passwordSetupToken,
                                        Instant passwordSetupExpiresAt) {}

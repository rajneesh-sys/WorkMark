package com.workmark.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompanyOnboardingResponse(UUID companyId, String companyName, UUID companyAdminId,
                                        String companyAdminEmail, String passwordSetupToken,
                                        Instant passwordSetupExpiresAt) {}

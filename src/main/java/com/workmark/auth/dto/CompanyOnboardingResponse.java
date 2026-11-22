package com.workmark.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record CompanyOnboardingResponse(UUID companyId, String companyName, UUID companyAdminId,
                                        String companyAdminEmail, String passwordSetupToken,
                                        Instant passwordSetupExpiresAt) {}

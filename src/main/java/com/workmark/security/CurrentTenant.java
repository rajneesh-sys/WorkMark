package com.workmark.security;

import com.workmark.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CurrentTenant {
    public CurrentUser user() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        return user;
    }

    public UUID companyId() {
        UUID companyId = user().getCompanyId();
        if (companyId == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This operation requires a company account");
        }
        return companyId;
    }
}

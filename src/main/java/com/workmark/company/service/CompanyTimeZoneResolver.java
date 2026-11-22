package com.workmark.company.service;

import com.workmark.common.exception.ApiException;
import com.workmark.company.repository.CompanyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.UUID;

@Service
public class CompanyTimeZoneResolver {
    private final CompanyRepository companies;

    public CompanyTimeZoneResolver(CompanyRepository companies) {
        this.companies = companies;
    }

    public ZoneId zoneFor(UUID companyId) {
        String zoneId = companies.findById(companyId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Company context is unavailable"))
                .getTimeZoneId();
        try {
            return ZoneId.of(zoneId);
        } catch (DateTimeException exception) {
            throw new IllegalStateException("Stored company timezone is invalid", exception);
        }
    }
}

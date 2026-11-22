package com.workmark.company.service;

import com.workmark.common.exception.ApiException;
import com.workmark.company.dto.CompanyRequest;
import com.workmark.company.dto.CompanyView;
import com.workmark.company.entity.Company;
import com.workmark.company.entity.CompanyStatus;
import com.workmark.company.repository.CompanyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.time.DateTimeException;
import java.time.ZoneId;

@Service
public class CompanyService {
    private final CompanyRepository companies;

    public CompanyService(CompanyRepository companies) { this.companies = companies; }

    @Transactional(readOnly = true)
    public CompanyView get(UUID companyId) { return view(find(companyId)); }

    @Transactional
    public CompanyView update(UUID companyId, CompanyRequest request) {
        Company company = find(companyId);
        if (!company.getEmail().equalsIgnoreCase(request.email())
                && companies.existsByEmailIgnoreCase(request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "Company email is already in use");
        }
        String timeZoneId = request.timeZoneId() == null ? company.getTimeZoneId() : request.timeZoneId();
        validateTimeZone(timeZoneId);
        company.update(request.companyName(), request.email(), request.phone(), request.address(), timeZoneId);
        return view(company);
    }

    @Transactional
    public CompanyView setStatus(UUID companyId, CompanyStatus status) {
        Company company = find(companyId);
        company.setStatus(status);
        return view(company);
    }

    private Company find(UUID id) {
        return companies.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Company not found"));
    }
    private CompanyView view(Company c) {
        return new CompanyView(c.getId(), c.getCompanyName(), c.getEmail(), c.getPhone(), c.getAddress(),
                c.getTimeZoneId(), c.getStatus(), c.getCreatedAt(), c.getUpdatedAt());
    }

    private void validateTimeZone(String timeZoneId) {
        try {
            ZoneId.of(timeZoneId);
        } catch (DateTimeException | NullPointerException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Company timeZoneId must be a valid IANA timezone");
        }
    }
}

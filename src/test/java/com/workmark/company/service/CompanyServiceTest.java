package com.workmark.company.service;

import com.workmark.common.exception.ApiException;
import com.workmark.company.dto.CompanyRequest;
import com.workmark.company.entity.Company;
import com.workmark.company.repository.CompanyRepository;
import com.workmark.company.service.CompanyStatusHistoryWriter;
import com.workmark.auth.entity.UserRole;
import com.workmark.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class CompanyServiceTest {
    @Test
    void rejectsInvalidCompanyTimezone() {
        CompanyRepository companies = mock(CompanyRepository.class);
        UUID companyId = UUID.randomUUID();
        Company company = new Company("Acme", "acme@example.com", null, null);
        when(companies.findById(companyId)).thenReturn(Optional.of(company));
        CompanyService service = new CompanyService(companies, mock(CompanyStatusHistoryWriter.class));

        ApiException error = assertThrows(ApiException.class, () -> service.update(companyId,
                new CompanyRequest("Acme", "acme@example.com", null, null, "Not/A-Timezone")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals("UTC", company.getTimeZoneId());
    }

    @Test
    void companyAdminCannotChangeCompanyStatusAtServiceLayer() {
        CompanyRepository companies = mock(CompanyRepository.class);
        CompanyService service = new CompanyService(companies, mock(CompanyStatusHistoryWriter.class));
        UUID companyId = UUID.randomUUID();
        CurrentUser admin = new CurrentUser(UUID.randomUUID(), companyId, null, "admin@example.com",
                "hash", UserRole.COMPANY_ADMIN, 0);

        ApiException error = assertThrows(ApiException.class,
                () -> service.setStatus(companyId, com.workmark.company.entity.CompanyStatus.INACTIVE, admin));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verifyNoInteractions(companies);
    }
}

package com.workmark.company.service;

import com.workmark.common.exception.ApiException;
import com.workmark.company.dto.CompanyRequest;
import com.workmark.company.entity.Company;
import com.workmark.company.repository.CompanyRepository;
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
        CompanyService service = new CompanyService(companies);

        ApiException error = assertThrows(ApiException.class, () -> service.update(companyId,
                new CompanyRequest("Acme", "acme@example.com", null, null, "Not/A-Timezone")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals("UTC", company.getTimeZoneId());
    }
}

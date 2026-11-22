package com.workmark.leave.service;

import com.workmark.auth.entity.UserRole;
import com.workmark.common.exception.ApiException;
import com.workmark.company.repository.CompanyRepository;
import com.workmark.company.service.CompanyTimeZoneResolver;
import com.workmark.employee.repository.EmployeeRepository;
import com.workmark.employee.service.EmployeeAccessPolicy;
import com.workmark.leave.dto.LeavePolicyRequest;
import com.workmark.leave.entity.LeaveType;
import com.workmark.leave.repository.LeaveBalanceEntryRepository;
import com.workmark.leave.repository.LeavePolicyRepository;
import com.workmark.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LeaveLedgerServiceTest {
    private final LeavePolicyRepository policies = mock(LeavePolicyRepository.class);
    private final LeaveBalanceEntryRepository entries = mock(LeaveBalanceEntryRepository.class);
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final EmployeeAccessPolicy accessPolicy = mock(EmployeeAccessPolicy.class);
    private final CompanyTimeZoneResolver timeZones = mock(CompanyTimeZoneResolver.class);

    @Test
    void rejectsCarryForwardExpiryUntilBalanceLotAccountingIsSupported() {
        UUID companyId = UUID.randomUUID();
        CurrentUser admin = new CurrentUser(UUID.randomUUID(), companyId, null,
                "admin@example.com", "hash", UserRole.COMPANY_ADMIN, 0);
        LeaveLedgerService service = new LeaveLedgerService(policies, entries, employees, accessPolicy,
                timeZones, Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC));

        ApiException error = assertThrows(ApiException.class, () -> service.setPolicy(companyId, admin,
                LeaveType.CASUAL, new LeavePolicyRequest(BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                        12, false, true)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(policies, never()).save(any());
    }
}

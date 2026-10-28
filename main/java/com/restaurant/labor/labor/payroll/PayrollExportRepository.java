package com.restaurant.labor.labor.payroll;

import com.restaurant.labor.common.ApiException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayrollExportRepository extends JpaRepository<PayrollExport, String> {

    Optional<PayrollExport> findByIdAndTenantId(String id, String tenantId);

    Optional<PayrollExport> findByTenantIdAndPayPeriodIdAndIdempotencyKey(String tenantId, String payPeriodId, String key);

    Optional<PayrollExport> findByTenantIdAndPayPeriodIdAndProviderAndStatus(String tenantId, String payPeriodId,
                                                                            String provider, PayrollExport.Status status);

    List<PayrollExport> findByTenantIdAndPayPeriodIdOrderByCreatedAtAsc(String tenantId, String payPeriodId);

    default PayrollExport require(String id, String tenantId) {
        return findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Payroll export"));
    }
}

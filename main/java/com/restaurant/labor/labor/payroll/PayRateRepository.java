package com.restaurant.labor.labor.payroll;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayRateRepository extends JpaRepository<PayRate, String> {

    List<PayRate> findByTenantIdAndEmployeeIdOrderByJobCodeAscEffectiveFromAsc(String tenantId, String employeeId);

    List<PayRate> findByTenantIdAndEmployeeIdAndJobCodeOrderByEffectiveFromAsc(String tenantId, String employeeId, String jobCode);
}

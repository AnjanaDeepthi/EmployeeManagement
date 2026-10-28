package com.restaurant.labor.labor.payroll;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriorPeriodAdjustmentRepository extends JpaRepository<PriorPeriodAdjustment, String> {

    List<PriorPeriodAdjustment> findByTenantIdAndPayPeriodIdOrderByCreatedAtAsc(String tenantId, String payPeriodId);
}

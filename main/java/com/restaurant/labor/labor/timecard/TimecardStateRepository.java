package com.restaurant.labor.labor.timecard;

import com.restaurant.labor.common.ApiException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TimecardStateRepository extends JpaRepository<TimecardState, String> {

    Optional<TimecardState> findByIdAndTenantId(String id, String tenantId);

    List<TimecardState> findByTenantIdAndLocationIdAndBusinessDateBetweenOrderByBusinessDateAscEmployeeIdAsc(
            String tenantId, String locationId, LocalDate from, LocalDate to);

    List<TimecardState> findByTenantIdAndEmployeeIdAndBusinessDateBetweenOrderByBusinessDateAsc(
            String tenantId, String employeeId, LocalDate from, LocalDate to);

    default TimecardState require(String id, String tenantId) {
        return findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Timecard"));
    }
}

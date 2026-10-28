package com.restaurant.labor.labor.schedule;

import com.restaurant.labor.common.ApiException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ScheduleRepository extends JpaRepository<Schedule, String> {

    Optional<Schedule> findByIdAndTenantId(String id, String tenantId);

    Optional<Schedule> findByTenantIdAndLocationIdAndWeekStart(String tenantId, String locationId, LocalDate weekStart);

    default Schedule require(String id, String tenantId) {
        return findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Schedule"));
    }
}

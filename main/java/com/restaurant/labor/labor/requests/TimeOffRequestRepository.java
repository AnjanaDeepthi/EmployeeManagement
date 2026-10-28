package com.restaurant.labor.labor.requests;

import com.restaurant.labor.common.ApiException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TimeOffRequestRepository extends JpaRepository<TimeOffRequest, String> {

    Optional<TimeOffRequest> findByIdAndTenantId(String id, String tenantId);

    List<TimeOffRequest> findByTenantIdAndEmployeeIdOrderByStartDateDesc(String tenantId, String employeeId);

    default TimeOffRequest require(String id, String tenantId) {
        return findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Time-off request"));
    }
}

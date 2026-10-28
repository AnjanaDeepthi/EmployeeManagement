package com.restaurant.labor.labor.schedule;

import com.restaurant.labor.common.ApiException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ScheduledShiftRepository extends JpaRepository<ScheduledShift, String> {

    Optional<ScheduledShift> findByIdAndTenantId(String id, String tenantId);

    List<ScheduledShift> findByTenantIdAndScheduleIdOrderByStartAtAsc(String tenantId, String scheduleId);

    /** Shifts of one employee that overlap [from, to). */
    @Query("""
            select s from ScheduledShift s
             where s.tenantId = :tenantId and s.employeeId = :employeeId
               and s.startAt < :to and s.endAt > :from
             order by s.startAt asc
            """)
    List<ScheduledShift> findForEmployeeBetween(@Param("tenantId") String tenantId,
                                                @Param("employeeId") String employeeId,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to);

    default ScheduledShift require(String id, String tenantId) {
        return findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Shift"));
    }
}

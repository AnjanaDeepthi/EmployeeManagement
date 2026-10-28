package com.restaurant.labor.labor.punch;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ClockStateRepository extends JpaRepository<ClockState, String> {

    /** Per-employee lock for the duration of the punch transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ClockState c where c.employeeId = :employeeId and c.tenantId = :tenantId")
    Optional<ClockState> lockForUpdate(@Param("employeeId") String employeeId, @Param("tenantId") String tenantId);

    Optional<ClockState> findByEmployeeIdAndTenantId(String employeeId, String tenantId);

    List<ClockState> findByTenantIdAndOpenLocationIdAndStateNot(String tenantId, String locationId, ClockState.State state);

    List<ClockState> findByStateNotAndOpenClockInAtBefore(ClockState.State state, Instant before);
}

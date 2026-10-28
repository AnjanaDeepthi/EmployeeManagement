package com.restaurant.labor.labor.punch;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PunchRepository extends JpaRepository<Punch, String> {

    Optional<Punch> findByTenantIdAndClientPunchId(String tenantId, String clientPunchId);

    Optional<Punch> findByIdAndTenantId(String id, String tenantId);

    List<Punch> findByTenantIdAndEmployeeIdAndBusinessDateOrderByOccurredAtAsc(String tenantId, String employeeId, LocalDate businessDate);

    @Query("""
            select p from Punch p
             where p.tenantId = :tenantId
               and (:employeeId is null or p.employeeId = :employeeId)
               and (:locationId is null or p.locationId = :locationId)
               and p.businessDate between :from and :to
               and p.id > :afterId
             order by p.id asc
            """)
    List<Punch> page(@Param("tenantId") String tenantId,
                     @Param("employeeId") String employeeId,
                     @Param("locationId") String locationId,
                     @Param("from") LocalDate from,
                     @Param("to") LocalDate to,
                     @Param("afterId") String afterId,
                     Limit limit);
}

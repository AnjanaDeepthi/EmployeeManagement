package com.restaurant.labor.labor.payroll;

import com.restaurant.labor.common.ApiException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PayPeriodRepository extends JpaRepository<PayPeriod, String> {

    Optional<PayPeriod> findByIdAndTenantId(String id, String tenantId);

    @Query("""
            select p from PayPeriod p
             where p.tenantId = :tenantId and p.locationId = :locationId
               and p.startDate <= :date and p.endDate >= :date
            """)
    Optional<PayPeriod> findCovering(@Param("tenantId") String tenantId, @Param("locationId") String locationId,
                                     @Param("date") LocalDate date);

    @Query("""
            select p from PayPeriod p
             where p.tenantId = :tenantId and p.locationId = :locationId
               and p.startDate <= :end and p.endDate >= :start
            """)
    List<PayPeriod> findOverlapping(@Param("tenantId") String tenantId, @Param("locationId") String locationId,
                                    @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("""
            select p from PayPeriod p
             where p.tenantId = :tenantId
               and (:locationId is null or p.locationId = :locationId)
               and (:status is null or p.status = :status)
             order by p.startDate desc
            """)
    List<PayPeriod> search(@Param("tenantId") String tenantId, @Param("locationId") String locationId,
                           @Param("status") PayPeriod.Status status);

    default PayPeriod require(String id, String tenantId) {
        return findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Pay period"));
    }
}

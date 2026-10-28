package com.restaurant.labor.employee;

import com.restaurant.labor.common.ApiException;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, String> {

    Optional<Employee> findByIdAndTenantId(String id, String tenantId);

    Optional<Employee> findByTenantIdAndEmailIgnoreCase(String tenantId, String email);

    /** Keyset page. {@code filterIds=false} ignores {@code ids} (pass a non-empty dummy list). */
    @Query("""
            select e from Employee e
             where e.tenantId = :tenantId
               and e.id > :afterId
               and (:status is null or e.status = :status)
               and (:filterIds = false or e.id in :ids)
             order by e.id asc
            """)
    List<Employee> page(@Param("tenantId") String tenantId,
                        @Param("afterId") String afterId,
                        @Param("status") EmployeeStatus status,
                        @Param("filterIds") boolean filterIds,
                        @Param("ids") Collection<String> ids,
                        Limit limit);

    /** Cross-tenant IDs return 404, never 403, so IDs can't be probed. */
    default Employee require(String id, String tenantId) {
        return findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Employee"));
    }
}

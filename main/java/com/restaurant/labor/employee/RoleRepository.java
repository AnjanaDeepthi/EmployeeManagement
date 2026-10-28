package com.restaurant.labor.employee;

import com.restaurant.labor.common.ApiException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, String> {

    List<Role> findByTenantIdOrderByNameAsc(String tenantId);

    Optional<Role> findByIdAndTenantId(String id, String tenantId);

    default Role require(String id, String tenantId) {
        return findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Role"));
    }
}

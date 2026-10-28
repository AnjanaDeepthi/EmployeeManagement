package com.restaurant.labor.location;

import com.restaurant.labor.common.ApiException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LocationRepository extends JpaRepository<Location, String> {

    Optional<Location> findByIdAndTenantId(String id, String tenantId);

    default Location require(String id, String tenantId) {
        return findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Location"));
    }
}

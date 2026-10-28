package com.restaurant.labor.labor.requests;

import com.restaurant.labor.common.ApiException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShiftSwapRequestRepository extends JpaRepository<ShiftSwapRequest, String> {

    Optional<ShiftSwapRequest> findByIdAndTenantId(String id, String tenantId);

    List<ShiftSwapRequest> findByTenantIdAndShiftIdAndStatusIn(String tenantId, String shiftId,
                                                               Collection<ShiftSwapRequest.Status> statuses);

    List<ShiftSwapRequest> findByTenantIdAndLocationIdAndStatusInOrderByCreatedAtAsc(
            String tenantId, String locationId, Collection<ShiftSwapRequest.Status> statuses);

    default ShiftSwapRequest require(String id, String tenantId) {
        return findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Swap request"));
    }
}

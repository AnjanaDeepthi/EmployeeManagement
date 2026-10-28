package com.restaurant.labor.employee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;

public interface PinSessionRepository extends JpaRepository<PinSession, String> {

    Optional<PinSession> findByTokenAndTenantId(String token, String tenantId);

    @Modifying
    void deleteByTenantIdAndEmployeeId(String tenantId, String employeeId);
}

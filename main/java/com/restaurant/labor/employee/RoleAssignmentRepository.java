package com.restaurant.labor.employee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, String> {

    List<RoleAssignment> findByTenantIdAndEmployeeIdAndStatus(String tenantId, String employeeId, RoleAssignment.Status status);

    List<RoleAssignment> findByTenantIdAndEmployeeIdOrderByGrantedAtDesc(String tenantId, String employeeId);

    List<RoleAssignment> findByTenantIdAndStatus(String tenantId, RoleAssignment.Status status);

    Optional<RoleAssignment> findByIdAndTenantIdAndEmployeeId(String id, String tenantId, String employeeId);

    Optional<RoleAssignment> findFirstByTenantIdAndEmployeeIdAndRoleIdAndScopeTypeAndScopeIdAndStatus(
            String tenantId, String employeeId, String roleId, ScopeType scopeType, String scopeId, RoleAssignment.Status status);
}

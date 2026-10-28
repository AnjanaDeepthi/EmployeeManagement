package com.restaurant.labor.employee;

import com.restaurant.labor.common.ETags;
import com.restaurant.labor.employee.EmployeeDtos.AssignmentResponse;
import com.restaurant.labor.employee.EmployeeDtos.CreateRoleRequest;
import com.restaurant.labor.employee.EmployeeDtos.GrantRoleRequest;
import com.restaurant.labor.employee.EmployeeDtos.PermissionResponse;
import com.restaurant.labor.employee.EmployeeDtos.RoleResponse;
import com.restaurant.labor.employee.EmployeeDtos.UpdateRoleRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Tag(name = "2. Roles and role assignments")
@RestController
public class RoleController {

    private final RoleService roles;
    private final AuthorizationService authz;

    public RoleController(RoleService roles, AuthorizationService authz) {
        this.roles = roles;
        this.authz = authz;
    }

    @Operation(summary = "Catalog of system-defined permissions")
    @GetMapping("/v1/permissions")
    public List<PermissionResponse> permissions() {
        return Arrays.stream(Permission.values()).map(p -> new PermissionResponse(p.code(), p.description())).toList();
    }

    @Operation(summary = "List system and custom roles")
    @GetMapping("/v1/roles")
    public List<RoleResponse> listRoles() {
        return roles.list().stream().map(RoleResponse::of).toList();
    }

    @GetMapping("/v1/roles/{roleId}")
    public ResponseEntity<RoleResponse> getRole(@PathVariable String roleId) {
        Role r = roles.get(roleId);
        return ResponseEntity.ok().eTag(ETags.of(r.getVersion())).body(RoleResponse.of(r));
    }

    @Operation(summary = "Create a custom role (only from permissions and limits you hold)")
    @PostMapping("/v1/roles")
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody CreateRoleRequest req) {
        Role r = roles.create(req);
        return ResponseEntity.created(URI.create("/v1/roles/" + r.getId())).eTag(ETags.of(r.getVersion())).body(RoleResponse.of(r));
    }

    @Operation(summary = "Edit a custom role (If-Match required; system roles are read-only)")
    @PatchMapping("/v1/roles/{roleId}")
    public ResponseEntity<RoleResponse> updateRole(@PathVariable String roleId,
                                                   @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                   @Valid @RequestBody UpdateRoleRequest req) {
        Role r = roles.update(roleId, req, ifMatch);
        return ResponseEntity.ok().eTag(ETags.of(r.getVersion())).body(RoleResponse.of(r));
    }

    @Operation(summary = "List an employee's role assignments (active and revoked)")
    @GetMapping("/v1/employees/{employeeId}/role_assignments")
    public List<AssignmentResponse> listAssignments(@PathVariable String employeeId) {
        String id = EmployeeService.resolve(employeeId);
        List<RoleAssignment> list = roles.assignmentsOf(id);
        Map<String, Role> byId = authz.rolesById(list.stream().map(RoleAssignment::getRoleId).toList());
        return list.stream().map(a -> AssignmentResponse.of(a, byId.get(a.getRoleId()))).toList();
    }

    @Operation(summary = "Grant a role at a scope (tenant or location). Blocks privilege escalation.")
    @PostMapping("/v1/employees/{employeeId}/role_assignments")
    public ResponseEntity<AssignmentResponse> grant(@PathVariable String employeeId, @Valid @RequestBody GrantRoleRequest req) {
        RoleService.GrantResult r = roles.grant(employeeId, req);
        AssignmentResponse body = AssignmentResponse.of(r.assignment(), roles.get(r.assignment().getRoleId()));
        if (!r.created()) return ResponseEntity.ok(body);
        return ResponseEntity.created(URI.create("/v1/employees/" + employeeId + "/role_assignments/" + r.assignment().getId()))
                .body(body);
    }

    @Operation(summary = "Revoke a role assignment (idempotent)")
    @DeleteMapping("/v1/employees/{employeeId}/role_assignments/{assignmentId}")
    public ResponseEntity<Void> revoke(@PathVariable String employeeId, @PathVariable String assignmentId) {
        roles.revoke(employeeId, assignmentId);
        return ResponseEntity.noContent().build();
    }
}

package com.restaurant.labor.employee;

import com.restaurant.labor.common.ApiException;
import com.restaurant.labor.common.AuditService;
import com.restaurant.labor.common.Caller;
import com.restaurant.labor.common.ETags;
import com.restaurant.labor.common.Ids;
import com.restaurant.labor.employee.EmployeeDtos.CreateRoleRequest;
import com.restaurant.labor.employee.EmployeeDtos.GrantRoleRequest;
import com.restaurant.labor.employee.EmployeeDtos.UpdateRoleRequest;
import com.restaurant.labor.location.LocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Service
public class RoleService {

    /** {@code created=false}: an identical active assignment already existed and was returned (idempotent). */
    public record GrantResult(RoleAssignment assignment, boolean created) {}

    private final RoleRepository roles;
    private final RoleAssignmentRepository assignments;
    private final EmployeeRepository employees;
    private final LocationRepository locations;
    private final AuthorizationService authz;
    private final AuditService audit;

    public RoleService(RoleRepository roles, RoleAssignmentRepository assignments, EmployeeRepository employees,
                       LocationRepository locations, AuthorizationService authz, AuditService audit) {
        this.roles = roles;
        this.assignments = assignments;
        this.employees = employees;
        this.locations = locations;
        this.authz = authz;
        this.audit = audit;
    }

    public List<Role> list() {
        return roles.findByTenantIdOrderByNameAsc(Caller.current().tenantId());
    }

    public Role get(String roleId) {
        return roles.require(roleId, Caller.current().tenantId());
    }

    @Transactional
    public Role create(CreateRoleRequest req) {
        Caller c = Caller.current();
        authz.require(Permission.ROLES_MANAGE, null);
        validatePermissionCodes(req.permissions());
        // No escalation: you can only build roles out of permissions and limits you hold yourself.
        checkNoEscalation(req.permissions(), req.refundLimitCents(), null, false);
        Role role = roles.save(new Role(Ids.next("role"), c.tenantId(), null, req.name(), false,
                req.permissions(), req.refundLimitCents()));
        audit.record("role.created", "role", role.getId(), "permissions=" + new TreeSet<>(req.permissions()));
        return role;
    }

    @Transactional
    public Role update(String roleId, UpdateRoleRequest req, String ifMatch) {
        Caller c = Caller.current();
        authz.require(Permission.ROLES_MANAGE, null);
        Role role = roles.require(roleId, c.tenantId());
        if (role.isSystem()) {
            throw ApiException.unprocessable("system_role_read_only", "System roles cannot be edited. Create a custom role instead.");
        }
        ETags.requireMatch(ifMatch, role.getVersion());
        Set<String> newPerms = req.permissions() != null ? req.permissions() : role.getPermissions();
        Long newLimit = req.refundLimitCents() != null ? req.refundLimitCents() : role.getRefundLimitCents();
        validatePermissionCodes(newPerms);
        checkNoEscalation(newPerms, newLimit, null, false);
        if (req.name() != null) role.setName(req.name());
        role.setPermissions(newPerms);
        role.setRefundLimitCents(newLimit);
        audit.record("role.updated", "role", role.getId(), "permissions=" + new TreeSet<>(newPerms));
        return roles.saveAndFlush(role);
    }

    /**
     * Grants a role at a scope. The server-side checks are the heart of this API:
     * <ol>
     *   <li>caller has employees:manage at the target scope;</li>
     *   <li>no privilege escalation: the role's permissions and limits must not exceed the caller's at that scope,
     *       and only owners can grant the owner role;</li>
     *   <li>target employee is in the tenant (404 otherwise) and not terminated or suspended;</li>
     *   <li>an identical active assignment is returned instead of duplicated (idempotent).</li>
     * </ol>
     */
    @Transactional
    public GrantResult grant(String employeeId, GrantRoleRequest req) {
        Caller c = Caller.current();
        String scopeLocation = validateScope(req.scope().type(), req.scope().id(), c.tenantId());

        authz.require(Permission.EMPLOYEES_MANAGE, scopeLocation);
        Role role = roles.require(req.roleId(), c.tenantId());
        checkNoEscalation(role.getPermissions(), role.getRefundLimitCents(), scopeLocation, role.isOwnerRole());

        Employee target = employees.require(employeeId, c.tenantId());
        if (target.getStatus() == EmployeeStatus.TERMINATED || target.getStatus() == EmployeeStatus.SUSPENDED) {
            throw ApiException.unprocessable("employee_not_active", "Roles can only be granted to active or invited employees.");
        }
        if (req.expiresAt() != null && !req.expiresAt().isAfter(Instant.now())) {
            throw ApiException.unprocessable("invalid_expiry", "expires_at must be in the future.");
        }

        var existing = assignments.findFirstByTenantIdAndEmployeeIdAndRoleIdAndScopeTypeAndScopeIdAndStatus(
                c.tenantId(), employeeId, role.getId(), req.scope().type(), req.scope().id(), RoleAssignment.Status.ACTIVE);
        if (existing.isPresent() && existing.get().isEffective(Instant.now())) {
            return new GrantResult(existing.get(), false);
        }

        RoleAssignment a = assignments.save(new RoleAssignment(Ids.next("ra"), c.tenantId(), employeeId, role.getId(),
                req.scope().type(), req.scope().id(), c.employeeId(), req.expiresAt(), req.reason()));
        audit.record("role_assignment.granted", "employee", employeeId,
                "role=" + role.getId() + ", scope=" + req.scope().type() + ":" + req.scope().id()
                        + (req.reason() == null ? "" : ", reason=" + req.reason()));
        // Production: publish employee.permissions_changed so permission caches are invalidated.
        return new GrantResult(a, true);
    }

    /** Revoking is idempotent: revoking an already-revoked assignment succeeds. */
    @Transactional
    public void revoke(String employeeId, String assignmentId) {
        Caller c = Caller.current();
        RoleAssignment a = assignments.findByIdAndTenantIdAndEmployeeId(assignmentId, c.tenantId(), employeeId)
                .orElseThrow(() -> ApiException.notFound("Role assignment"));
        String scopeLocation = a.getScopeType() == ScopeType.LOCATION ? a.getScopeId() : null;
        authz.require(Permission.EMPLOYEES_MANAGE, scopeLocation);
        Role role = roles.findById(a.getRoleId()).orElse(null);
        if (role != null && role.isOwnerRole() && !authz.effective(c.tenantId(), c.employeeId(), null).owner()) {
            throw ApiException.forbidden("escalation_denied", "Only owners can revoke the owner role.");
        }
        if (a.getStatus() == RoleAssignment.Status.REVOKED) return;
        a.revoke(c.employeeId());
        audit.record("role_assignment.revoked", "employee", employeeId, "assignment=" + assignmentId);
    }

    public List<RoleAssignment> assignmentsOf(String employeeId) {
        Caller c = Caller.current();
        employees.require(employeeId, c.tenantId());
        if (!employeeId.equals(c.employeeId())) {
            authz.requireOverEmployee(Permission.EMPLOYEES_READ, employeeId);
        }
        return assignments.findByTenantIdAndEmployeeIdOrderByGrantedAtDesc(c.tenantId(), employeeId);
    }

    // ---------------------------------------------------------------------------------------------

    /** @return the location id for LOCATION scope, or null for TENANT scope. */
    private String validateScope(ScopeType type, String id, String tenantId) {
        if (type == ScopeType.TENANT) {
            if (!tenantId.equals(id)) throw ApiException.notFound("Tenant");
            return null;
        }
        locations.require(id, tenantId);
        return id;
    }

    private void checkNoEscalation(Set<String> rolePerms, Long roleRefundLimit, String scopeLocation, boolean ownerRole) {
        Caller c = Caller.current();
        AuthorizationService.Effective mine = authz.effective(c.tenantId(), c.employeeId(), scopeLocation);
        if (ownerRole && !authz.effective(c.tenantId(), c.employeeId(), null).owner()) {
            throw ApiException.forbidden("escalation_denied", "Only owners can grant the owner role.");
        }
        Set<String> missing = new TreeSet<>(rolePerms);
        missing.removeAll(mine.permissions());
        if (!missing.isEmpty()) {
            throw ApiException.forbidden("escalation_denied",
                    "You cannot grant permissions you do not hold at this scope.").with("missing_permissions", missing);
        }
        Long myLimit = mine.refundLimitCents();
        boolean limitTooHigh = myLimit != null && (roleRefundLimit == null || roleRefundLimit > myLimit);
        if (limitTooHigh) {
            throw ApiException.forbidden("escalation_denied", "The role's refund limit exceeds your own.")
                    .with("your_refund_limit_cents", myLimit)
                    .with("role_refund_limit_cents", roleRefundLimit);
        }
    }

    private static void validatePermissionCodes(Set<String> codes) {
        List<String> unknown = codes.stream().filter(code -> Permission.fromCode(code).isEmpty()).sorted().toList();
        if (!unknown.isEmpty()) {
            throw ApiException.unprocessable("unknown_permission", "Unknown permission codes.").with("unknown", unknown);
        }
    }
}

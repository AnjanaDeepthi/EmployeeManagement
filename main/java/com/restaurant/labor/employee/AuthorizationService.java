package com.restaurant.labor.employee;

import com.restaurant.labor.common.ApiException;
import com.restaurant.labor.common.Caller;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Answers "can employee X do action Y at location Z?" from location-scoped role assignments.
 *
 * <p>An employee holds a permission at a location if they have an effective assignment whose scope covers
 * that location (a TENANT-scoped assignment covers every location) and whose role contains the permission.
 * For tenant-level actions (locationId == null) only TENANT-scoped assignments count.
 *
 * <p>Production note: other services would call this through a cached client (TTL ~60s) invalidated by
 * "employee.permissions_changed" events. Here it simply reads the database.
 */
@Service
public class AuthorizationService {

    /** Resolved permissions at one scope. {@code refundLimitCents == null} means unlimited. */
    public record Effective(Set<String> permissions, Long refundLimitCents, boolean owner) {
        public boolean has(Permission p) { return permissions.contains(p.code()); }
    }

    private final RoleAssignmentRepository assignments;
    private final RoleRepository roles;

    public AuthorizationService(RoleAssignmentRepository assignments, RoleRepository roles) {
        this.assignments = assignments;
        this.roles = roles;
    }

    public Effective effective(String tenantId, String employeeId, String locationId) {
        List<Role> held = effectiveAssignments(tenantId, employeeId).stream()
                .filter(a -> a.covers(locationId))
                .map(a -> roles.findById(a.getRoleId()).orElse(null))
                .filter(r -> r != null)
                .toList();
        Set<String> perms = new TreeSet<>();
        boolean unlimited = false;
        long maxLimit = 0;
        boolean owner = false;
        for (Role r : held) {
            perms.addAll(r.getPermissions());
            if (r.getRefundLimitCents() == null) unlimited = true;
            else maxLimit = Math.max(maxLimit, r.getRefundLimitCents());
            owner |= r.isOwnerRole();
        }
        Long limit = unlimited ? null : (held.isEmpty() ? 0L : maxLimit);
        return new Effective(perms, limit, owner);
    }

    public boolean has(String tenantId, String employeeId, Permission permission, String locationId) {
        return effective(tenantId, employeeId, locationId).has(permission);
    }

    /** Throws 403 unless the current caller holds the permission at the location (or tenant-wide). */
    public void require(Permission permission, String locationId) {
        Caller c = Caller.current();
        if (!has(c.tenantId(), c.employeeId(), permission, locationId)) {
            throw ApiException.forbidden("insufficient_permission",
                            "You need '" + permission.code() + "'" + (locationId == null ? " tenant-wide." : " at " + locationId + "."))
                    .with("permission", permission.code())
                    .with("location_id", locationId);
        }
    }

    /**
     * For acting on another employee (read, edit, terminate): the caller needs the permission tenant-wide,
     * or at one of the locations where the target employee works.
     */
    public void requireOverEmployee(Permission permission, String targetEmployeeId) {
        Caller c = Caller.current();
        if (has(c.tenantId(), c.employeeId(), permission, null)) return;
        // Past assignments count too: a GM must still be able to see (and pay) someone who just left.
        for (String loc : everLocationsOf(c.tenantId(), targetEmployeeId)) {
            if (has(c.tenantId(), c.employeeId(), permission, loc)) return;
        }
        throw ApiException.forbidden("insufficient_permission",
                        "You need '" + permission.code() + "' at a location where this employee works.")
                .with("permission", permission.code());
    }

    public boolean canActOnEmployee(Permission permission, String targetEmployeeId) {
        try {
            requireOverEmployee(permission, targetEmployeeId);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    /** True if the employee has any effective assignment covering the location. */
    public boolean isAssignedAt(String tenantId, String employeeId, String locationId) {
        return effectiveAssignments(tenantId, employeeId).stream().anyMatch(a -> a.covers(locationId));
    }

    /** Location IDs the employee is assigned to directly (tenant-wide assignments are not expanded). */
    public Set<String> locationsOf(String tenantId, String employeeId) {
        return effectiveAssignments(tenantId, employeeId).stream()
                .filter(a -> a.getScopeType() == ScopeType.LOCATION)
                .map(RoleAssignment::getScopeId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /** Every location the employee has ever been assigned to (any status), for read/management scope. */
    public Set<String> everLocationsOf(String tenantId, String employeeId) {
        return assignments.findByTenantIdAndEmployeeIdOrderByGrantedAtDesc(tenantId, employeeId).stream()
                .filter(a -> a.getScopeType() == ScopeType.LOCATION)
                .map(RoleAssignment::getScopeId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /** Employee IDs with an effective assignment matching the optional location and role filters. */
    public Set<String> employeesMatching(String tenantId, String locationId, String roleId) {
        Instant now = Instant.now();
        return assignments.findByTenantIdAndStatus(tenantId, RoleAssignment.Status.ACTIVE).stream()
                .filter(a -> a.isEffective(now))
                .filter(a -> locationId == null || a.covers(locationId))
                .filter(a -> roleId == null || roleId.equals(a.getRoleId()))
                .map(RoleAssignment::getEmployeeId)
                .collect(Collectors.toSet());
    }

    public Map<String, Role> rolesById(Collection<String> ids) {
        return roles.findAllById(ids).stream().collect(Collectors.toMap(Role::getId, Function.identity()));
    }

    private List<RoleAssignment> effectiveAssignments(String tenantId, String employeeId) {
        Instant now = Instant.now();
        return assignments.findByTenantIdAndEmployeeIdAndStatus(tenantId, employeeId, RoleAssignment.Status.ACTIVE)
                .stream().filter(a -> a.isEffective(now)).toList();
    }
}

package com.restaurant.labor.employee;

import com.restaurant.labor.common.ApiException;
import com.restaurant.labor.common.AuditService;
import com.restaurant.labor.common.Caller;
import com.restaurant.labor.common.CursorPage;
import com.restaurant.labor.common.ETags;
import com.restaurant.labor.common.Ids;
import com.restaurant.labor.employee.EmployeeDtos.EmployeeResponse;
import com.restaurant.labor.employee.EmployeeDtos.InviteEmployeeRequest;
import com.restaurant.labor.employee.EmployeeDtos.TerminationRequest;
import com.restaurant.labor.employee.EmployeeDtos.UpdateEmployeeRequest;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class EmployeeService {

    /** Result of an invite: {@code created=false} means an existing employee was returned (idempotent). */
    public record InviteResult(Employee employee, boolean created) {}

    private final EmployeeRepository employees;
    private final RoleAssignmentRepository assignments;
    private final AuthorizationService authz;
    private final PinService pins;
    private final AuditService audit;

    public EmployeeService(EmployeeRepository employees, RoleAssignmentRepository assignments,
                           AuthorizationService authz, PinService pins, AuditService audit) {
        this.employees = employees;
        this.assignments = assignments;
        this.authz = authz;
        this.pins = pins;
        this.audit = audit;
    }

    /**
     * Invites an employee. Inviting someone needs employees:manage somewhere in the tenant; their roles
     * (and therefore their locations) are granted afterwards through role assignments.
     * Re-inviting the same email returns the existing employee: a double-submitted form never creates duplicates.
     */
    @Transactional
    public InviteResult invite(InviteEmployeeRequest req) {
        Caller c = Caller.current();
        boolean canManageSomewhere = authz.has(c.tenantId(), c.employeeId(), Permission.EMPLOYEES_MANAGE, null)
                || authz.locationsOf(c.tenantId(), c.employeeId()).stream()
                .anyMatch(loc -> authz.has(c.tenantId(), c.employeeId(), Permission.EMPLOYEES_MANAGE, loc));
        if (!canManageSomewhere) {
            throw ApiException.forbidden("insufficient_permission", "You need 'employees:manage' to invite employees.");
        }
        var existing = employees.findByTenantIdAndEmailIgnoreCase(c.tenantId(), req.email());
        if (existing.isPresent()) {
            return new InviteResult(existing.get(), false);
        }
        Employee e = new Employee(Ids.next("emp"), c.tenantId(), req.name(), req.email().toLowerCase(), req.phone(),
                EmployeeStatus.INVITED, req.hireDate(), Boolean.TRUE.equals(req.minor()));
        e.setExternalPayrollId(req.externalPayrollId());
        employees.save(e);
        audit.record("employee.invited", "employee", e.getId(), req.email());
        // A real system would send the invitation email through the Notification API here.
        return new InviteResult(e, true);
    }

    /** The invited employee accepts their own invitation (simulates finishing sign-up). */
    @Transactional
    public Employee acceptInvitation(String employeeId) {
        Caller c = Caller.current();
        if (!c.employeeId().equals(employeeId)) {
            throw ApiException.forbidden("not_self", "Only the invited employee can accept the invitation.");
        }
        Employee e = employees.require(employeeId, c.tenantId());
        if (e.getStatus() == EmployeeStatus.ACTIVE) return e; // idempotent
        if (e.getStatus() != EmployeeStatus.INVITED) {
            throw ApiException.unprocessable("invalid_state", "Only invited employees can accept an invitation.");
        }
        e.activate();
        audit.record("employee.activated", "employee", e.getId(), null);
        return e;
    }

    @Transactional(readOnly = true)
    public CursorPage<EmployeeResponse> list(String locationId, String roleId, EmployeeStatus status,
                                             String cursor, Integer limit) {
        Caller c = Caller.current();
        if (locationId != null) {
            authz.require(Permission.EMPLOYEES_READ, locationId);
        } else {
            authz.require(Permission.EMPLOYEES_READ, null);
        }
        boolean filter = locationId != null || roleId != null;
        Set<String> ids = filter ? authz.employeesMatching(c.tenantId(), locationId, roleId) : Set.of("-");
        if (filter && ids.isEmpty()) {
            return new CursorPage<>(List.of(), null, false);
        }
        int n = CursorPage.clampLimit(limit);
        List<Employee> rows = employees.page(c.tenantId(), CursorPage.decode(cursor), status, filter, ids, Limit.of(n + 1));
        return CursorPage.of(rows, n, Employee::getId, EmployeeResponse::of);
    }

    @Transactional(readOnly = true)
    public Employee get(String employeeId) {
        Caller c = Caller.current();
        Employee e = employees.require(resolve(employeeId), c.tenantId());
        if (!e.getId().equals(c.employeeId())) {
            authz.requireOverEmployee(Permission.EMPLOYEES_READ, e.getId());
        }
        return e;
    }

    @Transactional
    public Employee update(String employeeId, UpdateEmployeeRequest req, String ifMatch) {
        Caller c = Caller.current();
        Employee e = employees.require(resolve(employeeId), c.tenantId());
        boolean self = e.getId().equals(c.employeeId());
        if (!self) {
            authz.requireOverEmployee(Permission.EMPLOYEES_MANAGE, e.getId());
        } else if (req.email() != null || req.minor() != null || req.hireDate() != null) {
            // Employees may edit their own name and phone, but not HR fields.
            authz.requireOverEmployee(Permission.EMPLOYEES_MANAGE, e.getId());
        }
        ETags.requireMatch(ifMatch, e.getVersion());
        if (req.name() != null) e.setName(req.name());
        if (req.phone() != null) e.setPhone(req.phone());
        if (req.email() != null) e.setEmail(req.email().toLowerCase());
        if (req.hireDate() != null) e.setHireDate(req.hireDate());
        if (req.minor() != null) e.setMinor(req.minor());
        audit.record("employee.updated", "employee", e.getId(), null);
        return employees.saveAndFlush(e);
    }

    /**
     * Termination is a state change, never a delete: past punches, timecards and audit entries keep pointing
     * at this employee. All role assignments and POS sessions are revoked immediately.
     */
    @Transactional
    public Employee terminate(String employeeId, TerminationRequest req) {
        Caller c = Caller.current();
        Employee e = employees.require(employeeId, c.tenantId());
        if (e.getId().equals(c.employeeId())) {
            throw ApiException.unprocessable("cannot_terminate_self", "You cannot terminate yourself.");
        }
        authz.requireOverEmployee(Permission.EMPLOYEES_MANAGE, e.getId());
        if (e.getStatus() == EmployeeStatus.TERMINATED) {
            return e; // idempotent
        }
        assignments.findByTenantIdAndEmployeeIdAndStatus(c.tenantId(), e.getId(), RoleAssignment.Status.ACTIVE)
                .forEach(a -> a.revoke(c.employeeId()));
        pins.endAllSessions(c.tenantId(), e.getId());
        e.terminate();
        audit.record("employee.terminated", "employee", e.getId(),
                "reason=" + req.reason() + (req.lastDay() == null ? "" : ", last_day=" + req.lastDay()));
        return e;
    }

    /** Supports "me" as an alias for the caller. */
    public static String resolve(String employeeId) {
        return "me".equals(employeeId) ? Caller.current().employeeId() : employeeId;
    }
}

package com.restaurant.labor.employee;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Request and response bodies for the Employee / Role API. JSON uses snake_case. */
public final class EmployeeDtos {

    private EmployeeDtos() {}

    // ---- employees ----

    public record InviteEmployeeRequest(
            @NotBlank String name,
            @NotBlank @Email String email,
            String phone,
            LocalDate hireDate,
            Boolean minor,
            String externalPayrollId) {}

    /** Merge-patch style: only non-null fields change. */
    public record UpdateEmployeeRequest(String name, @Email String email, String phone, LocalDate hireDate, Boolean minor) {}

    public record TerminationRequest(@NotBlank String reason, LocalDate lastDay) {}

    public record EmployeeResponse(String id, String name, String email, String phone, EmployeeStatus status,
                                   LocalDate hireDate, boolean minor, Instant createdAt, Instant terminatedAt) {
        static EmployeeResponse of(Employee e) {
            return new EmployeeResponse(e.getId(), e.getName(), e.getEmail(), e.getPhone(), e.getStatus(),
                    e.getHireDate(), e.isMinor(), e.getCreatedAt(), e.getTerminatedAt());
        }
    }

    // ---- roles & permissions ----

    public record CreateRoleRequest(
            @NotBlank String name,
            @NotEmpty Set<String> permissions,
            @PositiveOrZero Long refundLimitCents) {}

    /** Only non-null fields change. */
    public record UpdateRoleRequest(String name, Set<String> permissions, @PositiveOrZero Long refundLimitCents) {}

    public record RoleResponse(String id, String name, String systemKey, boolean system,
                               Set<String> permissions, Long refundLimitCents) {
        static RoleResponse of(Role r) {
            return new RoleResponse(r.getId(), r.getName(), r.getSystemKey(), r.isSystem(),
                    new java.util.TreeSet<>(r.getPermissions()), r.getRefundLimitCents());
        }
    }

    public record PermissionResponse(String code, String description) {}

    // ---- role assignments ----

    public record ScopeDto(@NotNull ScopeType type, @NotBlank String id) {}

    public record GrantRoleRequest(@NotBlank String roleId, @NotNull @Valid ScopeDto scope,
                                   Instant expiresAt, String reason) {}

    public record RoleRef(String id, String name) {}

    public record AssignmentResponse(String id, String employeeId, RoleRef role, ScopeDto scope,
                                     RoleAssignment.Status status, boolean effective, String grantedBy,
                                     Instant grantedAt, Instant expiresAt, String reason,
                                     String revokedBy, Instant revokedAt) {
        static AssignmentResponse of(RoleAssignment a, Role role) {
            return new AssignmentResponse(a.getId(), a.getEmployeeId(),
                    new RoleRef(a.getRoleId(), role == null ? null : role.getName()),
                    new ScopeDto(a.getScopeType(), a.getScopeId()),
                    a.getStatus(), a.isEffective(Instant.now()), a.getGrantedBy(), a.getGrantedAt(),
                    a.getExpiresAt(), a.getReason(), a.getRevokedBy(), a.getRevokedAt());
        }
    }

    // ---- authorization ----

    public record EffectivePermissionsResponse(String employeeId, String locationId,
                                               Set<String> permissions, Long refundLimitCents) {}

    public record AuthorizationCheck(@NotBlank String employeeId, @NotBlank String permission, String locationId) {}

    public record AuthorizationCheckRequest(@NotEmpty @Size(max = 100) List<@Valid AuthorizationCheck> checks) {}

    public record AuthorizationCheckResult(String employeeId, String permission, String locationId, boolean allowed) {}

    public record AuthorizationCheckResponse(List<AuthorizationCheckResult> results) {}

    // ---- POS PIN ----

    public record SetPinRequest(@NotBlank @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4 to 6 digits") String pin) {}

    public record PinSessionRequest(@NotBlank String employeeId, @NotBlank String pin,
                                    @NotBlank String locationId, @NotBlank String deviceId) {}

    public record PinSessionResponse(String token, String employeeId, String locationId, Instant expiresAt) {}

    // ---- audit ----

    public record AuditResponse(String id, String actorId, String action, String targetType, String targetId,
                                String details, Instant occurredAt) {}
}

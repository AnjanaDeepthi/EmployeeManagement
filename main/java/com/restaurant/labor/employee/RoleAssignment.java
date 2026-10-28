package com.restaurant.labor.employee;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Employee x Role x Scope. "Maria is a shift lead at Fort Worth until Oct 15." */
@Entity
@Table(name = "role_assignments")
public class RoleAssignment {

    public enum Status { ACTIVE, REVOKED }

    @Id
    private String id;
    private String tenantId;
    private String employeeId;
    private String roleId;
    @Enumerated(EnumType.STRING)
    private ScopeType scopeType;
    /** Tenant id for TENANT scope, location id for LOCATION scope. */
    private String scopeId;
    private String grantedBy;
    private Instant grantedAt;
    private Instant expiresAt;
    private String reason;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String revokedBy;
    private Instant revokedAt;

    protected RoleAssignment() {}

    public RoleAssignment(String id, String tenantId, String employeeId, String roleId, ScopeType scopeType,
                          String scopeId, String grantedBy, Instant expiresAt, String reason) {
        this.id = id;
        this.tenantId = tenantId;
        this.employeeId = employeeId;
        this.roleId = roleId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.grantedBy = grantedBy;
        this.grantedAt = Instant.now();
        this.expiresAt = expiresAt;
        this.reason = reason;
        this.status = Status.ACTIVE;
    }

    /** Active and not past its expiry. */
    public boolean isEffective(Instant now) {
        return status == Status.ACTIVE && (expiresAt == null || expiresAt.isAfter(now));
    }

    /** Does this assignment apply to the given location (null = a tenant-level action)? */
    public boolean covers(String locationId) {
        if (scopeType == ScopeType.TENANT) return true;
        return locationId != null && locationId.equals(scopeId);
    }

    public void revoke(String actor) {
        this.status = Status.REVOKED;
        this.revokedBy = actor;
        this.revokedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEmployeeId() { return employeeId; }
    public String getRoleId() { return roleId; }
    public ScopeType getScopeType() { return scopeType; }
    public String getScopeId() { return scopeId; }
    public String getGrantedBy() { return grantedBy; }
    public Instant getGrantedAt() { return grantedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getReason() { return reason; }
    public Status getStatus() { return status; }
    public String getRevokedBy() { return revokedBy; }
    public Instant getRevokedAt() { return revokedAt; }
}

package com.restaurant.labor.employee;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "employees", uniqueConstraints = @UniqueConstraint(columnNames = {"tenantId", "email"}))
public class Employee {

    @Id
    private String id;
    private String tenantId;
    private String name;
    private String email;
    private String phone;
    @Enumerated(EnumType.STRING)
    private EmployeeStatus status;
    private LocalDate hireDate;
    /** Under 18: labor rules such as curfews apply. */
    private boolean minor;
    private String externalPayrollId;

    // POS PIN (stored hashed) and brute-force protection
    private String pinHash;
    private int failedPinAttempts;
    private Instant pinLockedUntil;

    private Instant createdAt;
    private Instant terminatedAt;

    /** Null until first saved, so Spring Data treats the entity as new and persists it. */
    @Version
    private Long version;

    protected Employee() {}

    public Employee(String id, String tenantId, String name, String email, String phone,
                    EmployeeStatus status, LocalDate hireDate, boolean minor) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.status = status;
        this.hireDate = hireDate;
        this.minor = minor;
        this.createdAt = Instant.now();
    }

    public void activate() { this.status = EmployeeStatus.ACTIVE; }

    public void terminate() {
        this.status = EmployeeStatus.TERMINATED;
        this.terminatedAt = Instant.now();
        this.pinHash = null;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public EmployeeStatus getStatus() { return status; }
    public void setStatus(EmployeeStatus status) { this.status = status; }
    public LocalDate getHireDate() { return hireDate; }
    public void setHireDate(LocalDate hireDate) { this.hireDate = hireDate; }
    public boolean isMinor() { return minor; }
    public void setMinor(boolean minor) { this.minor = minor; }
    public String getExternalPayrollId() { return externalPayrollId; }
    public void setExternalPayrollId(String externalPayrollId) { this.externalPayrollId = externalPayrollId; }
    public String getPinHash() { return pinHash; }
    public void setPinHash(String pinHash) { this.pinHash = pinHash; }
    public int getFailedPinAttempts() { return failedPinAttempts; }
    public void setFailedPinAttempts(int failedPinAttempts) { this.failedPinAttempts = failedPinAttempts; }
    public Instant getPinLockedUntil() { return pinLockedUntil; }
    public void setPinLockedUntil(Instant pinLockedUntil) { this.pinLockedUntil = pinLockedUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getTerminatedAt() { return terminatedAt; }
    public long getVersion() { return version == null ? 0 : version; }
}

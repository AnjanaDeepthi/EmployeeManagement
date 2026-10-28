package com.restaurant.labor.labor.payroll;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Effective-dated hourly rate per employee and job. Kept apart from the Employee record because it is
 * sensitive (payroll:read) and because a mid-period raise needs two rates covering different dates.
 */
@Entity
@Table(name = "pay_rates")
public class PayRate {

    @Id
    private String id;
    private String tenantId;
    private String employeeId;
    private String jobCode;
    private long hourlyCents;
    private LocalDate effectiveFrom;
    /** Inclusive; null = still in effect. */
    private LocalDate effectiveTo;
    private String createdBy;
    private Instant createdAt;

    protected PayRate() {}

    public PayRate(String id, String tenantId, String employeeId, String jobCode, long hourlyCents,
                   LocalDate effectiveFrom, String createdBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.employeeId = employeeId;
        this.jobCode = jobCode;
        this.hourlyCents = hourlyCents;
        this.effectiveFrom = effectiveFrom;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public boolean appliesOn(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    public void endOn(LocalDate lastDay) { this.effectiveTo = lastDay; }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEmployeeId() { return employeeId; }
    public String getJobCode() { return jobCode; }
    public long getHourlyCents() { return hourlyCents; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}

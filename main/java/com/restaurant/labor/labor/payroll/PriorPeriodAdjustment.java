package com.restaurant.labor.labor.payroll;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A correction for a date inside an already-locked pay period. Locked periods never change (payroll may
 * have paid them); the difference is paid in the current open period instead, with a reference to the original date.
 */
@Entity
@Table(name = "prior_period_adjustments")
public class PriorPeriodAdjustment {

    @Id
    private String id;
    private String tenantId;
    private String employeeId;
    private String locationId;
    /** The open period that will pay the difference. */
    private String payPeriodId;
    private LocalDate originalBusinessDate;
    /** Positive = unpaid time to add; negative = overpaid time to recover. */
    private long minutesDelta;
    private String reasonCode;
    private String note;
    private String actorId;
    private Instant createdAt;

    protected PriorPeriodAdjustment() {}

    public PriorPeriodAdjustment(String id, String tenantId, String employeeId, String locationId, String payPeriodId,
                                 LocalDate originalBusinessDate, long minutesDelta, String reasonCode, String note,
                                 String actorId) {
        this.id = id;
        this.tenantId = tenantId;
        this.employeeId = employeeId;
        this.locationId = locationId;
        this.payPeriodId = payPeriodId;
        this.originalBusinessDate = originalBusinessDate;
        this.minutesDelta = minutesDelta;
        this.reasonCode = reasonCode;
        this.note = note;
        this.actorId = actorId;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEmployeeId() { return employeeId; }
    public String getLocationId() { return locationId; }
    public String getPayPeriodId() { return payPeriodId; }
    public LocalDate getOriginalBusinessDate() { return originalBusinessDate; }
    public long getMinutesDelta() { return minutesDelta; }
    public String getReasonCode() { return reasonCode; }
    public String getNote() { return note; }
    public String getActorId() { return actorId; }
    public Instant getCreatedAt() { return createdAt; }
}

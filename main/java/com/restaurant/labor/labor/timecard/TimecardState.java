package com.restaurant.labor.labor.timecard;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Workflow state of one employee's timecard for one business date. The hours themselves are not stored here:
 * they are computed from punches + adjustments every time (see TimecardCalculator).
 * The version is the timecard's ETag; adjustments must send If-Match.
 */
@Entity
@Table(name = "timecards")
public class TimecardState {

    public enum Status { OPEN, APPROVED, LOCKED }

    @Id
    private String id;
    private String tenantId;
    private String employeeId;
    private String locationId;
    private LocalDate businessDate;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String approvedBy;
    private Instant approvedAt;
    private boolean needsReview;
    private String reviewReason;
    private Instant lastChangedAt;
    /** Null until first saved, so Spring Data treats the entity as new and persists it. */
    @Version
    private Long version;

    protected TimecardState() {}

    public TimecardState(String tenantId, String employeeId, String locationId, LocalDate businessDate) {
        this.id = idFor(employeeId, businessDate);
        this.tenantId = tenantId;
        this.employeeId = employeeId;
        this.locationId = locationId;
        this.businessDate = businessDate;
        this.status = Status.OPEN;
    }

    public static String idFor(String employeeId, LocalDate businessDate) {
        return "tc_" + employeeId + "_" + businessDate.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    /** Any change to the underlying punches re-opens an approved timecard: it must be approved again. */
    public void changed() {
        if (status == Status.APPROVED) {
            status = Status.OPEN;
            approvedBy = null;
            approvedAt = null;
        }
        // Touch the row so Hibernate bumps @Version even if nothing else changed: clients' ETags go stale.
        lastChangedAt = Instant.now();
    }

    public void approve(String by) {
        status = Status.APPROVED;
        approvedBy = by;
        approvedAt = Instant.now();
        needsReview = false;
        reviewReason = null;
    }

    public void lock() { status = Status.LOCKED; }

    public void flagForReview(String reason) {
        needsReview = true;
        reviewReason = reason;
    }

    public void clearReview() {
        needsReview = false;
        reviewReason = null;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEmployeeId() { return employeeId; }
    public String getLocationId() { return locationId; }
    public LocalDate getBusinessDate() { return businessDate; }
    public Status getStatus() { return status; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public boolean isNeedsReview() { return needsReview; }
    public String getReviewReason() { return reviewReason; }
    public Instant getLastChangedAt() { return lastChangedAt; }
    public long getVersion() { return version == null ? 0 : version; }
}

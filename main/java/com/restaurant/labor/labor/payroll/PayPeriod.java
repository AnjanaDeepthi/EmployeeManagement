package com.restaurant.labor.labor.payroll;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;

/** OPEN -> LOCKED (no more edits) -> EXPORTED (sent to the payroll provider). */
@Entity
@Table(name = "pay_periods")
public class PayPeriod {

    public enum Status { OPEN, LOCKED, EXPORTED }

    @Id
    private String id;
    private String tenantId;
    private String locationId;
    private LocalDate startDate;
    private LocalDate endDate;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String lockedBy;
    private Instant lockedAt;
    /** Null until first saved, so Spring Data treats the entity as new and persists it. */
    @Version
    private Long version;

    protected PayPeriod() {}

    public PayPeriod(String id, String tenantId, String locationId, LocalDate startDate, LocalDate endDate) {
        this.id = id;
        this.tenantId = tenantId;
        this.locationId = locationId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = Status.OPEN;
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    public boolean isClosed() { return status != Status.OPEN; }

    public void lock(String by) {
        status = Status.LOCKED;
        lockedBy = by;
        lockedAt = Instant.now();
    }

    public void markExported() { status = Status.EXPORTED; }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getLocationId() { return locationId; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public Status getStatus() { return status; }
    public String getLockedBy() { return lockedBy; }
    public Instant getLockedAt() { return lockedAt; }
    public long getVersion() { return version == null ? 0 : version; }
}

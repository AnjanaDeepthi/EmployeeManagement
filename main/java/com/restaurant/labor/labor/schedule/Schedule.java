package com.restaurant.labor.labor.schedule;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;

/** One location's schedule for one workweek. Employees only see it once it is published. */
@Entity
@Table(name = "schedules", uniqueConstraints = @UniqueConstraint(columnNames = {"tenantId", "locationId", "weekStart"}))
public class Schedule {

    public enum Status { DRAFT, PUBLISHED }

    @Id
    private String id;
    private String tenantId;
    private String locationId;
    private LocalDate weekStart;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String createdBy;
    private Instant publishedAt;
    private String publishedBy;
    /** Null until first saved, so Spring Data treats the entity as new and persists it. */
    @Version
    private Long version;

    protected Schedule() {}

    public Schedule(String id, String tenantId, String locationId, LocalDate weekStart, String createdBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.locationId = locationId;
        this.weekStart = weekStart;
        this.createdBy = createdBy;
        this.status = Status.DRAFT;
    }

    public void publish(String by) {
        this.status = Status.PUBLISHED;
        this.publishedAt = Instant.now();
        this.publishedBy = by;
    }

    public LocalDate weekEnd() { return weekStart.plusDays(6); }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getLocationId() { return locationId; }
    public LocalDate getWeekStart() { return weekStart; }
    public Status getStatus() { return status; }
    public String getCreatedBy() { return createdBy; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getPublishedBy() { return publishedBy; }
    public long getVersion() { return version == null ? 0 : version; }
}

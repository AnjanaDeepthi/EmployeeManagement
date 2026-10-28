package com.restaurant.labor.labor.schedule;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "scheduled_shifts")
public class ScheduledShift {

    @Id
    private String id;
    private String tenantId;
    private String scheduleId;
    private String locationId;
    private String employeeId;
    private String jobCode;
    private Instant startAt;
    private Instant endAt;
    private String notes;
    /** Set when the shift changes after the schedule was published (predictive-scheduling laws care). */
    private boolean changedAfterPublish;
    /** Null until first saved, so Spring Data treats the entity as new and persists it. */
    @Version
    private Long version;

    protected ScheduledShift() {}

    public ScheduledShift(String id, String tenantId, String scheduleId, String locationId, String employeeId,
                          String jobCode, Instant startAt, Instant endAt, String notes) {
        this.id = id;
        this.tenantId = tenantId;
        this.scheduleId = scheduleId;
        this.locationId = locationId;
        this.employeeId = employeeId;
        this.jobCode = jobCode;
        this.startAt = startAt;
        this.endAt = endAt;
        this.notes = notes;
    }

    public long minutes() { return Duration.between(startAt, endAt).toMinutes(); }

    public boolean overlaps(Instant start, Instant end) {
        return startAt.isBefore(end) && start.isBefore(endAt);
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getScheduleId() { return scheduleId; }
    public String getLocationId() { return locationId; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }
    public String getJobCode() { return jobCode; }
    public void setJobCode(String jobCode) { this.jobCode = jobCode; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isChangedAfterPublish() { return changedAfterPublish; }
    public void markChangedAfterPublish() { this.changedAfterPublish = true; }
    public long getVersion() { return version == null ? 0 : version; }
}

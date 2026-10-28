package com.restaurant.labor.labor.punch;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;

/**
 * An immutable clock event. There are no setters: punches are facts. Corrections are recorded as
 * TimecardAdjustments, so the original punch always survives for wage-and-hour audits.
 */
@Entity
@Table(name = "punches", uniqueConstraints = @UniqueConstraint(columnNames = {"tenantId", "clientPunchId"}))
public class Punch {

    @Id
    private String id;
    private String tenantId;
    private String employeeId;
    private String locationId;
    @Enumerated(EnumType.STRING)
    private PunchType type;
    /** Device clock at the moment of the tap. */
    private Instant occurredAt;
    /** Server clock when we received it (differs for offline replays). */
    private Instant receivedAt;
    @Enumerated(EnumType.STRING)
    private PunchSource source;
    private String recordedBy;
    private String deviceId;
    private String jobCode;
    /** Generated on the device; makes retries and offline replays idempotent. */
    private String clientPunchId;
    private String scheduledShiftId;
    private LocalDate businessDate;
    private String flags;

    protected Punch() {}

    public Punch(String id, String tenantId, String employeeId, String locationId, PunchType type, Instant occurredAt,
                 PunchSource source, String recordedBy, String deviceId, String jobCode, String clientPunchId,
                 String scheduledShiftId, LocalDate businessDate, String flags) {
        this.id = id;
        this.tenantId = tenantId;
        this.employeeId = employeeId;
        this.locationId = locationId;
        this.type = type;
        this.occurredAt = occurredAt;
        this.receivedAt = Instant.now();
        this.source = source;
        this.recordedBy = recordedBy;
        this.deviceId = deviceId;
        this.jobCode = jobCode;
        this.clientPunchId = clientPunchId;
        this.scheduledShiftId = scheduledShiftId;
        this.businessDate = businessDate;
        this.flags = flags;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEmployeeId() { return employeeId; }
    public String getLocationId() { return locationId; }
    public PunchType getType() { return type; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public PunchSource getSource() { return source; }
    public String getRecordedBy() { return recordedBy; }
    public String getDeviceId() { return deviceId; }
    public String getJobCode() { return jobCode; }
    public String getClientPunchId() { return clientPunchId; }
    public String getScheduledShiftId() { return scheduledShiftId; }
    public LocalDate getBusinessDate() { return businessDate; }
    public String getFlags() { return flags; }
}

package com.restaurant.labor.labor.timecard;

import com.restaurant.labor.labor.punch.PunchType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** A manager's correction to a timecard. Punches are never edited; this record says what changed, who, and why. */
@Entity
@Table(name = "timecard_adjustments")
public class TimecardAdjustment {

    public enum Action { ADD_PUNCH, MOVE_PUNCH, VOID_PUNCH }

    @Id
    private String id;
    private String tenantId;
    private String timecardId;
    private String employeeId;
    private LocalDate businessDate;
    @Enumerated(EnumType.STRING)
    private Action action;
    /** The punch being moved or voided (null for ADD_PUNCH). */
    private String punchId;
    /** For ADD_PUNCH: which kind of punch is added. */
    @Enumerated(EnumType.STRING)
    private PunchType punchType;
    /** For ADD_PUNCH and MOVE_PUNCH: the corrected time. */
    private Instant occurredAt;
    private Instant previousOccurredAt;
    private String jobCode;
    private String reasonCode;
    private String note;
    private String actorId;
    private Instant createdAt;

    protected TimecardAdjustment() {}

    public TimecardAdjustment(String id, String tenantId, String timecardId, String employeeId, LocalDate businessDate,
                              Action action, String punchId, PunchType punchType, Instant occurredAt,
                              Instant previousOccurredAt, String jobCode, String reasonCode, String note, String actorId) {
        this.id = id;
        this.tenantId = tenantId;
        this.timecardId = timecardId;
        this.employeeId = employeeId;
        this.businessDate = businessDate;
        this.action = action;
        this.punchId = punchId;
        this.punchType = punchType;
        this.occurredAt = occurredAt;
        this.previousOccurredAt = previousOccurredAt;
        this.jobCode = jobCode;
        this.reasonCode = reasonCode;
        this.note = note;
        this.actorId = actorId;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getTimecardId() { return timecardId; }
    public String getEmployeeId() { return employeeId; }
    public LocalDate getBusinessDate() { return businessDate; }
    public Action getAction() { return action; }
    public String getPunchId() { return punchId; }
    public PunchType getPunchType() { return punchType; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getPreviousOccurredAt() { return previousOccurredAt; }
    public String getJobCode() { return jobCode; }
    public String getReasonCode() { return reasonCode; }
    public String getNote() { return note; }
    public String getActorId() { return actorId; }
    public Instant getCreatedAt() { return createdAt; }
}

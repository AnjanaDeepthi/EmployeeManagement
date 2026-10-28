package com.restaurant.labor.labor.punch;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row per employee: are they on the clock right now? Locked (SELECT ... FOR UPDATE) while recording a punch,
 * so two terminals can't record conflicting punches for the same person at the same moment.
 */
@Entity
@Table(name = "clock_states")
public class ClockState {

    public enum State { OFF, ON, ON_BREAK }

    @Id
    private String employeeId;
    private String tenantId;
    @Enumerated(EnumType.STRING)
    private State state;
    private String openClockInPunchId;
    private Instant openClockInAt;
    private String openLocationId;
    private LocalDate openBusinessDate;
    private String openJobCode;
    private Instant lastPunchAt;

    protected ClockState() {}

    public ClockState(String employeeId, String tenantId) {
        this.employeeId = employeeId;
        this.tenantId = tenantId;
        this.state = State.OFF;
    }

    public void clockIn(Punch p) {
        state = State.ON;
        openClockInPunchId = p.getId();
        openClockInAt = p.getOccurredAt();
        openLocationId = p.getLocationId();
        openBusinessDate = p.getBusinessDate();
        openJobCode = p.getJobCode();
        lastPunchAt = p.getOccurredAt();
    }

    public void apply(PunchType type, Instant at) {
        switch (type) {
            case BREAK_START -> state = State.ON_BREAK;
            case BREAK_END -> state = State.ON;
            case CLOCK_OUT -> close();
            default -> throw new IllegalArgumentException("Use clockIn for CLOCK_IN");
        }
        lastPunchAt = at;
    }

    /** Closes the open shift (normal clock-out, or a manager adjustment that added the missing clock-out). */
    public void close() {
        state = State.OFF;
        openClockInPunchId = null;
        openClockInAt = null;
        openLocationId = null;
        openBusinessDate = null;
        openJobCode = null;
    }

    public String getEmployeeId() { return employeeId; }
    public String getTenantId() { return tenantId; }
    public State getState() { return state; }
    public String getOpenClockInPunchId() { return openClockInPunchId; }
    public Instant getOpenClockInAt() { return openClockInAt; }
    public String getOpenLocationId() { return openLocationId; }
    public LocalDate getOpenBusinessDate() { return openBusinessDate; }
    public String getOpenJobCode() { return openJobCode; }
    public Instant getLastPunchAt() { return lastPunchAt; }
}

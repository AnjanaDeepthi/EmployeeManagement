package com.restaurant.labor.labor.requests;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "time_off_requests")
public class TimeOffRequest {

    public enum Status { REQUESTED, APPROVED, DENIED, CANCELLED }

    @Id
    private String id;
    private String tenantId;
    private String employeeId;
    private LocalDate startDate;
    private LocalDate endDate;
    private String reason;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    private Instant createdAt;

    protected TimeOffRequest() {}

    public TimeOffRequest(String id, String tenantId, String employeeId, LocalDate startDate, LocalDate endDate, String reason) {
        this.id = id;
        this.tenantId = tenantId;
        this.employeeId = employeeId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.reason = reason;
        this.status = Status.REQUESTED;
        this.createdAt = Instant.now();
    }

    public void decide(Status decision, String by, String note) {
        this.status = decision;
        this.decidedBy = by;
        this.decidedAt = Instant.now();
        this.decisionNote = note;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEmployeeId() { return employeeId; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getReason() { return reason; }
    public Status getStatus() { return status; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public Instant getCreatedAt() { return createdAt; }
}

package com.restaurant.labor.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Append-only audit trail: who did what to which resource, and why. */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    private String id;
    private String tenantId;
    private String actorId;
    private String action;
    private String targetType;
    private String targetId;
    @Column(length = 2000)
    private String details;
    private Instant occurredAt;

    protected AuditEvent() {}

    public AuditEvent(String tenantId, String actorId, String action, String targetType, String targetId, String details) {
        this.id = Ids.next("aud");
        this.tenantId = tenantId;
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.details = details;
        this.occurredAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getActorId() { return actorId; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getDetails() { return details; }
    public Instant getOccurredAt() { return occurredAt; }
}

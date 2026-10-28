package com.restaurant.labor.labor.requests;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An employee offers their shift. Either they name a colleague, or it's an open offer someone picks up.
 * A manager approves before the shift actually changes hands.
 */
@Entity
@Table(name = "shift_swap_requests")
public class ShiftSwapRequest {

    public enum Status { OFFERED, PENDING_APPROVAL, APPROVED, DENIED, CANCELLED }

    @Id
    private String id;
    private String tenantId;
    private String shiftId;
    private String locationId;
    private String offeredBy;
    private String pickupEmployeeId;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String note;
    private String decidedBy;
    private Instant decidedAt;
    private Instant createdAt;

    protected ShiftSwapRequest() {}

    public ShiftSwapRequest(String id, String tenantId, String shiftId, String locationId, String offeredBy,
                            String pickupEmployeeId, String note) {
        this.id = id;
        this.tenantId = tenantId;
        this.shiftId = shiftId;
        this.locationId = locationId;
        this.offeredBy = offeredBy;
        this.pickupEmployeeId = pickupEmployeeId;
        this.note = note;
        this.status = pickupEmployeeId == null ? Status.OFFERED : Status.PENDING_APPROVAL;
        this.createdAt = Instant.now();
    }

    public void pickUp(String employeeId) {
        this.pickupEmployeeId = employeeId;
        this.status = Status.PENDING_APPROVAL;
    }

    public void decide(Status decision, String by) {
        this.status = decision;
        this.decidedBy = by;
        this.decidedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getShiftId() { return shiftId; }
    public String getLocationId() { return locationId; }
    public String getOfferedBy() { return offeredBy; }
    public String getPickupEmployeeId() { return pickupEmployeeId; }
    public Status getStatus() { return status; }
    public String getNote() { return note; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getCreatedAt() { return createdAt; }
}

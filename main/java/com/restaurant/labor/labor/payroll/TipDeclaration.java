package com.restaurant.labor.labor.payroll;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** Cash tips an employee declares (usually at clock-out). Card tips would come from the POS. */
@Entity
@Table(name = "tip_declarations")
public class TipDeclaration {

    @Id
    private String id;
    private String tenantId;
    private String employeeId;
    private String locationId;
    private LocalDate businessDate;
    private long cashTipsCents;
    private Instant declaredAt;

    protected TipDeclaration() {}

    public TipDeclaration(String id, String tenantId, String employeeId, String locationId, LocalDate businessDate,
                          long cashTipsCents) {
        this.id = id;
        this.tenantId = tenantId;
        this.employeeId = employeeId;
        this.locationId = locationId;
        this.businessDate = businessDate;
        this.cashTipsCents = cashTipsCents;
        this.declaredAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEmployeeId() { return employeeId; }
    public String getLocationId() { return locationId; }
    public LocalDate getBusinessDate() { return businessDate; }
    public long getCashTipsCents() { return cashTipsCents; }
    public Instant getDeclaredAt() { return declaredAt; }
}

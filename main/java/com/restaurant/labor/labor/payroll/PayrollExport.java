package com.restaurant.labor.labor.payroll;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/** At most one SUCCEEDED export per (pay period, provider). A re-export must explicitly supersede the previous one. */
@Entity
@Table(name = "payroll_exports")
public class PayrollExport {

    public enum Status { SUCCEEDED, SUPERSEDED }

    @Id
    private String id;
    private String tenantId;
    private String payPeriodId;
    private String provider;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String supersedesId;
    private String idempotencyKey;
    private String checksum;
    private String providerConfirmationId;
    private int employeeCount;
    private long grossWagesCents;
    @Lob
    @Column(columnDefinition = "CLOB")
    private String payload;
    private String createdBy;
    private Instant createdAt;

    protected PayrollExport() {}

    public PayrollExport(String id, String tenantId, String payPeriodId, String provider, String supersedesId,
                         String idempotencyKey, String checksum, String providerConfirmationId, int employeeCount,
                         long grossWagesCents, String payload, String createdBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.payPeriodId = payPeriodId;
        this.provider = provider;
        this.status = Status.SUCCEEDED;
        this.supersedesId = supersedesId;
        this.idempotencyKey = idempotencyKey;
        this.checksum = checksum;
        this.providerConfirmationId = providerConfirmationId;
        this.employeeCount = employeeCount;
        this.grossWagesCents = grossWagesCents;
        this.payload = payload;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void supersede() { this.status = Status.SUPERSEDED; }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getPayPeriodId() { return payPeriodId; }
    public String getProvider() { return provider; }
    public Status getStatus() { return status; }
    public String getSupersedesId() { return supersedesId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getChecksum() { return checksum; }
    public String getProviderConfirmationId() { return providerConfirmationId; }
    public int getEmployeeCount() { return employeeCount; }
    public long getGrossWagesCents() { return grossWagesCents; }
    public String getPayload() { return payload; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}

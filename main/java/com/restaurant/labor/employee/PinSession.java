package com.restaurant.labor.employee;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A short-lived POS sign-in created by entering a PIN on an enrolled device. */
@Entity
@Table(name = "pin_sessions")
public class PinSession {

    @Id
    private String token;
    private String tenantId;
    private String employeeId;
    private String locationId;
    private String deviceId;
    private Instant expiresAt;

    protected PinSession() {}

    public PinSession(String token, String tenantId, String employeeId, String locationId, String deviceId, Instant expiresAt) {
        this.token = token;
        this.tenantId = tenantId;
        this.employeeId = employeeId;
        this.locationId = locationId;
        this.deviceId = deviceId;
        this.expiresAt = expiresAt;
    }

    public String getToken() { return token; }
    public String getTenantId() { return tenantId; }
    public String getEmployeeId() { return employeeId; }
    public String getLocationId() { return locationId; }
    public String getDeviceId() { return deviceId; }
    public Instant getExpiresAt() { return expiresAt; }
}

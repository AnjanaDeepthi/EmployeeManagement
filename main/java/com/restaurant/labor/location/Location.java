package com.restaurant.labor.location;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalTime;
import java.time.ZoneId;

/** The labor-relevant settings of a restaurant location (the full Restaurant API owns the rest). */
@Entity
@Table(name = "locations")
public class Location {

    @Id
    private String id;
    private String tenantId;
    private String name;
    private String timeZone;
    /** Sales and shifts before this local time belong to the previous business date (e.g. 04:00). */
    private LocalTime businessDayCutoff;
    @Enumerated(EnumType.STRING)
    private RuleSet ruleSet;
    /** How many minutes before a scheduled shift an employee may clock in without a manager. */
    private int earlyClockInGraceMinutes;
    /** Minors may not be on the clock at or after this local time. */
    private LocalTime minorCurfew;

    protected Location() {}

    public Location(String id, String tenantId, String name, String timeZone, LocalTime businessDayCutoff,
                    RuleSet ruleSet, int earlyClockInGraceMinutes, LocalTime minorCurfew) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.timeZone = timeZone;
        this.businessDayCutoff = businessDayCutoff;
        this.ruleSet = ruleSet;
        this.earlyClockInGraceMinutes = earlyClockInGraceMinutes;
        this.minorCurfew = minorCurfew;
    }

    public ZoneId zone() { return ZoneId.of(timeZone); }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getTimeZone() { return timeZone; }
    public LocalTime getBusinessDayCutoff() { return businessDayCutoff; }
    public RuleSet getRuleSet() { return ruleSet; }
    public int getEarlyClockInGraceMinutes() { return earlyClockInGraceMinutes; }
    public LocalTime getMinorCurfew() { return minorCurfew; }
}

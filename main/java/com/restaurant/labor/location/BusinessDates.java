package com.restaurant.labor.location;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * Business date = the restaurant's trading day. A shift that starts at 5 PM and ends at 1 AM belongs to one
 * business date: anything before the location's cutoff (e.g. 04:00 local) counts toward the previous day.
 */
public final class BusinessDates {

    /** Workweeks start on Monday (configurable per employer in a real system). */
    public static final DayOfWeek WEEK_START = DayOfWeek.MONDAY;

    private BusinessDates() {}

    public static LocalDate of(Instant at, ZoneId zone, LocalTime cutoff) {
        return at.atZone(zone).toLocalDateTime()
                .minusHours(cutoff.getHour())
                .minusMinutes(cutoff.getMinute())
                .toLocalDate();
    }

    public static LocalDate of(Instant at, Location location) {
        return of(at, location.zone(), location.getBusinessDayCutoff());
    }

    public static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(WEEK_START));
    }
}

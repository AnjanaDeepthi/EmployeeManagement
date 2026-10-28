package com.restaurant.labor.labor.payroll;

import com.restaurant.labor.location.BusinessDates;
import com.restaurant.labor.location.RuleSet;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure function: splits worked minutes into regular, overtime and double time per day, by jurisdiction.
 *
 * <ul>
 *   <li>US federal: overtime after 40 hours in a workweek.</li>
 *   <li>California: overtime after 8 hours in a day, double time after 12, and overtime after 40 regular
 *       hours in a workweek. (The seventh-consecutive-day rule is left out to keep the demo small.)</li>
 * </ul>
 *
 * Callers should pass every day of each workweek touched by a pay period (including days before the period
 * starts), so that weekly overtime is computed on the whole week.
 */
public final class OvertimeCalculator {

    public static final int WEEKLY_REGULAR_LIMIT_MINUTES = 40 * 60;
    static final int CA_DAILY_OT_AFTER = 8 * 60;
    static final int CA_DAILY_DT_AFTER = 12 * 60;

    public record Split(long regular, long overtime, long doubleTime) {
        public long total() { return regular + overtime + doubleTime; }
    }

    private OvertimeCalculator() {}

    public static Map<LocalDate, Split> split(RuleSet rules, Map<LocalDate, Long> workedByDay) {
        Map<LocalDate, Long> days = new TreeMap<>(workedByDay);
        Map<LocalDate, Split> result = new LinkedHashMap<>();
        LocalDate currentWeek = null;
        long weeklyRegular = 0;

        for (Map.Entry<LocalDate, Long> e : days.entrySet()) {
            LocalDate week = BusinessDates.weekStart(e.getKey());
            if (!week.equals(currentWeek)) {
                currentWeek = week;
                weeklyRegular = 0;
            }
            long worked = e.getValue();
            long regular;
            long overtime;
            long doubleTime;
            if (rules == RuleSet.US_CALIFORNIA) {
                doubleTime = Math.max(0, worked - CA_DAILY_DT_AFTER);
                overtime = Math.max(0, Math.min(worked, CA_DAILY_DT_AFTER) - CA_DAILY_OT_AFTER);
                regular = Math.min(worked, CA_DAILY_OT_AFTER);
            } else {
                doubleTime = 0;
                overtime = 0;
                regular = worked;
            }
            // Weekly rule (both jurisdictions): regular minutes beyond 40h in the workweek become overtime.
            long room = Math.max(0, WEEKLY_REGULAR_LIMIT_MINUTES - weeklyRegular);
            if (regular > room) {
                overtime += regular - room;
                regular = room;
            }
            weeklyRegular += regular;
            result.put(e.getKey(), new Split(regular, overtime, doubleTime));
        }
        return result;
    }

    /** Sum of splits, e.g. for a pay-period total. */
    public static Split total(List<Split> splits) {
        long r = 0, o = 0, d = 0;
        for (Split s : splits) { r += s.regular(); o += s.overtime(); d += s.doubleTime(); }
        return new Split(r, o, d);
    }
}

package com.restaurant.labor.labor;

import com.restaurant.labor.labor.payroll.OvertimeCalculator;
import com.restaurant.labor.labor.punch.PunchType;
import com.restaurant.labor.labor.timecard.TimecardCalculator;
import com.restaurant.labor.labor.timecard.TimecardCalculator.EffectivePunch;
import com.restaurant.labor.location.BusinessDates;
import com.restaurant.labor.location.RuleSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain unit tests for the framework-free calculators (no Spring context). */
class CalculatorTest {

    private static final Instant T = Instant.parse("2026-09-25T15:00:00Z");

    private static EffectivePunch p(String id, PunchType type, long minutesAfterStart) {
        return new EffectivePunch(id, type, T.plusSeconds(minutesAfterStart * 60), "line_cook");
    }

    @Test
    void computesWorkedAndBreakMinutes() {
        var r = TimecardCalculator.calculate(List.of(
                p("1", PunchType.CLOCK_IN, 0), p("2", PunchType.BREAK_START, 240),
                p("3", PunchType.BREAK_END, 270), p("4", PunchType.CLOCK_OUT, 540)), Instant.now(), true);
        assertThat(r.workedMinutes()).isEqualTo(510);
        assertThat(r.breakMinutes()).isEqualTo(30);
        assertThat(r.open()).isFalse();
        assertThat(r.violations()).isEmpty();
    }

    @Test
    void flagsMissedMealBreakWhenTheRuleApplies() {
        var punches = List.of(p("1", PunchType.CLOCK_IN, 0), p("2", PunchType.CLOCK_OUT, 6 * 60));
        assertThat(TimecardCalculator.calculate(punches, Instant.now(), true).violations()).contains("meal_break_missed");
        assertThat(TimecardCalculator.calculate(punches, Instant.now(), false).violations()).isEmpty();
    }

    @Test
    void openShiftAndSequenceErrors() {
        var open = TimecardCalculator.calculate(List.of(p("1", PunchType.CLOCK_IN, 0)), T.plusSeconds(3600), false);
        assertThat(open.open()).isTrue();
        assertThat(open.workedMinutes()).isEqualTo(60);

        var bad = TimecardCalculator.calculate(List.of(p("1", PunchType.CLOCK_IN, 0), p("2", PunchType.CLOCK_IN, 10)),
                Instant.now(), false);
        assertThat(bad.flags()).contains("sequence_error");
    }

    @Test
    void federalOvertimeIsWeekly() {
        Map<LocalDate, Long> days = fiveDaysOf(10 * 60);
        var total = OvertimeCalculator.total(List.copyOf(OvertimeCalculator.split(RuleSet.US_FEDERAL, days).values()));
        assertThat(total.regular()).isEqualTo(2400);
        assertThat(total.overtime()).isEqualTo(600);
    }

    @Test
    void californiaOvertimeIsDailyAndDoubleTimeAfterTwelveHours() {
        Map<LocalDate, Long> days = new TreeMap<>();
        days.put(LocalDate.of(2026, 9, 21), 13L * 60);
        var split = OvertimeCalculator.split(RuleSet.US_CALIFORNIA, days).get(LocalDate.of(2026, 9, 21));
        assertThat(split.regular()).isEqualTo(480);
        assertThat(split.overtime()).isEqualTo(240);
        assertThat(split.doubleTime()).isEqualTo(60);
    }

    @Test
    void lateNightWorkBelongsToThePreviousBusinessDate() {
        // 1:00 AM in Chicago on Sep 26 is still the Sep 25 business day (cutoff 04:00).
        Instant oneAm = Instant.parse("2026-09-26T06:00:00Z");
        assertThat(BusinessDates.of(oneAm, ZoneId.of("America/Chicago"), LocalTime.of(4, 0)))
                .isEqualTo(LocalDate.of(2026, 9, 25));
    }

    private static Map<LocalDate, Long> fiveDaysOf(long minutes) {
        Map<LocalDate, Long> days = new TreeMap<>();
        LocalDate monday = LocalDate.of(2026, 9, 21);
        for (int i = 0; i < 5; i++) days.put(monday.plusDays(i), minutes);
        return days;
    }
}

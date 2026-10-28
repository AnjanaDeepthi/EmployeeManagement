package com.restaurant.labor.labor.timecard;

import com.restaurant.labor.labor.punch.PunchType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure function: turns one business day's effective punches into worked and break segments.
 * Timecards are never stored as "hours"; they are always computed from the immutable punch log plus adjustments.
 * Framework-free on purpose, so it is easy to unit test.
 */
public final class TimecardCalculator {

    /** A punch after adjustments have been applied. */
    public record EffectivePunch(String punchId, PunchType type, Instant at, String jobCode) {}

    public enum SegmentType { WORK, BREAK }

    public record Segment(SegmentType type, Instant start, Instant end, String jobCode, boolean open) {
        public long minutes() { return Duration.between(start, end).toMinutes(); }
    }

    public record Result(List<Segment> segments, long workedMinutes, long breakMinutes, boolean open,
                         Set<String> flags, Set<String> violations, String primaryJobCode) {}

    /** California-style rule: a 30-minute meal break is due once more than 5 hours are worked. */
    static final long MEAL_BREAK_DUE_AFTER_MINUTES = 5 * 60;
    static final long MEAL_BREAK_MIN_MINUTES = 30;

    private TimecardCalculator() {}

    /**
     * @param now                 used to measure a shift that is still open (for live totals)
     * @param mealBreakRule       whether the location's jurisdiction requires meal breaks
     */
    public static Result calculate(List<EffectivePunch> punches, Instant now, boolean mealBreakRule) {
        List<EffectivePunch> sorted = new ArrayList<>(punches);
        sorted.sort(Comparator.comparing(EffectivePunch::at));

        List<Segment> segments = new ArrayList<>();
        Set<String> flags = new LinkedHashSet<>();
        Set<String> violations = new LinkedHashSet<>();

        SegmentType current = null;   // null = off the clock
        Instant segmentStart = null;
        String job = null;
        String primaryJob = null;

        for (EffectivePunch p : sorted) {
            switch (p.type()) {
                case CLOCK_IN -> {
                    if (current != null) { flags.add("sequence_error"); continue; }
                    current = SegmentType.WORK;
                    segmentStart = p.at();
                    job = p.jobCode();
                    if (primaryJob == null) primaryJob = job;
                }
                case BREAK_START -> {
                    if (current != SegmentType.WORK) { flags.add("sequence_error"); continue; }
                    segments.add(new Segment(SegmentType.WORK, segmentStart, p.at(), job, false));
                    current = SegmentType.BREAK;
                    segmentStart = p.at();
                }
                case BREAK_END -> {
                    if (current != SegmentType.BREAK) { flags.add("sequence_error"); continue; }
                    segments.add(new Segment(SegmentType.BREAK, segmentStart, p.at(), null, false));
                    current = SegmentType.WORK;
                    segmentStart = p.at();
                }
                case CLOCK_OUT -> {
                    if (current == null) { flags.add("sequence_error"); continue; }
                    segments.add(new Segment(current, segmentStart, p.at(), current == SegmentType.WORK ? job : null, false));
                    current = null;
                    segmentStart = null;
                }
            }
        }

        boolean open = current != null;
        if (open) {
            Instant end = now.isAfter(segmentStart) ? now : segmentStart;
            segments.add(new Segment(current, segmentStart, end, current == SegmentType.WORK ? job : null, true));
            flags.add("open_shift");
        }

        long worked = segments.stream().filter(s -> s.type() == SegmentType.WORK).mapToLong(Segment::minutes).sum();
        long breaks = segments.stream().filter(s -> s.type() == SegmentType.BREAK).mapToLong(Segment::minutes).sum();

        if (mealBreakRule && worked > MEAL_BREAK_DUE_AFTER_MINUTES) {
            boolean hadMealBreak = segments.stream()
                    .anyMatch(s -> s.type() == SegmentType.BREAK && s.minutes() >= MEAL_BREAK_MIN_MINUTES);
            if (!hadMealBreak) violations.add("meal_break_missed");
        }
        return new Result(segments, worked, breaks, open, flags, violations, primaryJob);
    }
}

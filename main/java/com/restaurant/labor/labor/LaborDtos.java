package com.restaurant.labor.labor;

import com.restaurant.labor.labor.punch.PunchSource;
import com.restaurant.labor.labor.punch.PunchType;
import com.restaurant.labor.labor.timecard.TimecardAdjustment;
import com.restaurant.labor.labor.timecard.TimecardCalculator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Request and response bodies for the Labor Management API. JSON uses snake_case.
 * Request times are OffsetDateTime (so "2026-09-25T15:58:12-05:00" works); responses use UTC instants.
 */
public final class LaborDtos {

    private LaborDtos() {}

    // ---------------- scheduling ----------------

    public record CreateScheduleRequest(@NotNull LocalDate weekStart) {}

    public record ShiftResponse(String id, String scheduleId, String locationId, String employeeId, String jobCode,
                                Instant startAt, Instant endAt, long minutes, String notes,
                                boolean changedAfterPublish, long version) {}

    public record ScheduleResponse(String id, String locationId, LocalDate weekStart, LocalDate weekEnd, String status,
                                   Instant publishedAt, String publishedBy, long version, List<ShiftResponse> shifts) {}

    public record CreateShiftRequest(@NotBlank String employeeId, @NotBlank String jobCode,
                                     @NotNull OffsetDateTime startAt, @NotNull OffsetDateTime endAt, String notes) {}

    /** Only non-null fields change. */
    public record UpdateShiftRequest(String employeeId, String jobCode, OffsetDateTime startAt, OffsetDateTime endAt,
                                     String notes) {}

    // ---------------- punches ----------------

    public record PunchRequest(
            /* Generated on the device, unique per device. Retries and offline replays reuse it. */
            @NotBlank String clientPunchId,
            /* Defaults to the caller. A different employee requires punches:write_others (manager override). */
            String employeeId,
            @NotBlank String locationId,
            @NotNull PunchType type,
            /* Required for CLOCK_IN. */
            String jobCode,
            /* Device time of the tap; defaults to now. */
            OffsetDateTime occurredAt,
            String deviceId,
            String scheduledShiftId,
            /* True when the device queued this punch while offline (skips the clock-drift check). */
            Boolean offline) {}

    public record Warning(String code, String detail) {}

    public record WeekToDate(long workedMinutes, long overtimeThresholdMinutes) {}

    public record PunchResponse(String id, String employeeId, String locationId, PunchType type, Instant occurredAt,
                                Instant receivedAt, LocalDate businessDate, PunchSource source, String recordedBy,
                                String jobCode, String scheduledShiftId, String timecardId, List<String> flags,
                                WeekToDate weekToDate, List<Warning> warnings) {}

    public record OnTheClockEntry(String employeeId, String name, String state, String jobCode, Instant clockedInAt,
                                  long minutesOnClock, LocalDate businessDate) {}

    // ---------------- timecards ----------------

    public record SegmentDto(TimecardCalculator.SegmentType type, Instant start, Instant end, long minutes,
                             String jobCode, boolean open) {}

    public record EffectivePunchDto(String punchId, PunchType type, Instant at, String jobCode, boolean adjusted) {}

    public record AdjustmentDto(String id, TimecardAdjustment.Action action, String punchId, PunchType punchType,
                                Instant occurredAt, Instant previousOccurredAt, String jobCode, String reasonCode,
                                String note, String actorId, Instant createdAt) {}

    public record TimecardResponse(String id, String employeeId, String locationId, LocalDate businessDate,
                                   String status, long version, long workedMinutes, long breakMinutes, boolean open,
                                   Set<String> flags, Set<String> violations, boolean needsReview, String reviewReason,
                                   String approvedBy, Instant approvedAt, List<SegmentDto> segments,
                                   List<EffectivePunchDto> punches, List<AdjustmentDto> adjustments) {}

    public record AdjustmentRequest(
            @NotNull TimecardAdjustment.Action action,
            /* MOVE_PUNCH / VOID_PUNCH: id of the punch (or of an earlier ADD_PUNCH adjustment). */
            String punchId,
            /* ADD_PUNCH: the kind of punch to add, e.g. CLOCK_OUT for a forgotten clock-out. */
            PunchType punchType,
            /* ADD_PUNCH / MOVE_PUNCH: the corrected time. */
            OffsetDateTime occurredAt,
            String jobCode,
            /* e.g. forgot_clock_out, wrong_job_code, system_error */
            @NotBlank String reasonCode,
            String note) {}

    // ---------------- pay rates, tips, pay periods ----------------

    public record PayRateRequest(@NotBlank String jobCode, @Positive long hourlyCents, @NotNull LocalDate effectiveFrom) {}

    public record PayRateResponse(String id, String employeeId, String jobCode, long hourlyCents,
                                  LocalDate effectiveFrom, LocalDate effectiveTo) {}

    public record TipDeclarationRequest(@NotBlank String locationId, @NotNull LocalDate businessDate,
                                        @PositiveOrZero long cashTipsCents) {}

    public record TipDeclarationResponse(String id, String employeeId, String locationId, LocalDate businessDate,
                                         long cashTipsCents, Instant declaredAt) {}

    public record CreatePayPeriodRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate) {}

    public record PayPeriodResponse(String id, String locationId, LocalDate startDate, LocalDate endDate, String status,
                                    String lockedBy, Instant lockedAt) {}

    public record PriorPeriodAdjustmentRequest(@NotBlank String employeeId, @NotNull LocalDate originalBusinessDate,
                                               long minutesDelta, @NotBlank String reasonCode, String note) {}

    public record PriorPeriodAdjustmentResponse(String id, String employeeId, String payPeriodId,
                                                LocalDate originalBusinessDate, long minutesDelta, String reasonCode,
                                                String note, String actorId, Instant createdAt) {}

    public record DayLine(LocalDate businessDate, String jobCode, long regularMinutes, long overtimeMinutes,
                          long doubleTimeMinutes, Long hourlyCents) {}

    public record EmployeePaySummary(String employeeId, String name, long regularMinutes, long overtimeMinutes,
                                     long doubleTimeMinutes, long priorPeriodAdjustmentMinutes, long grossWagesCents,
                                     long cashTipsCents, List<DayLine> days, List<String> warnings) {}

    public record PayTotals(long regularMinutes, long overtimeMinutes, long doubleTimeMinutes, long grossWagesCents,
                            long cashTipsCents) {}

    public record PayPeriodSummary(String payPeriodId, String locationId, LocalDate startDate, LocalDate endDate,
                                   String status, String ruleSet, List<EmployeePaySummary> employees, PayTotals totals) {}

    public record ExportRequest(@NotBlank String provider, String supersedes) {}

    public record ExportResponse(String id, String payPeriodId, String provider, String status, String supersedesId,
                                 String checksum, String providerConfirmationId, int employeeCount,
                                 long grossWagesCents, String createdBy, Instant createdAt) {}

    // ---------------- time off and swaps ----------------

    public record TimeOffCreateRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate, String reason) {}

    public record TimeOffResponse(String id, String employeeId, LocalDate startDate, LocalDate endDate, String reason,
                                  String status, String decidedBy, Instant decidedAt, String decisionNote,
                                  List<String> conflictingShiftIds) {}

    public record SwapOfferRequest(String pickupEmployeeId, String note) {}

    public record SwapResponse(String id, String shiftId, String locationId, String offeredBy, String pickupEmployeeId,
                               String status, String note, String decidedBy, Instant decidedAt) {}

    public record DecisionRequest(String note) {}
}

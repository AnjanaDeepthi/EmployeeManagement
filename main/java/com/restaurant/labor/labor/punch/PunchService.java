package com.restaurant.labor.labor.punch;

import com.restaurant.labor.common.ApiException;
import com.restaurant.labor.common.AuditService;
import com.restaurant.labor.common.Caller;
import com.restaurant.labor.common.CursorPage;
import com.restaurant.labor.common.Ids;
import com.restaurant.labor.employee.AuthorizationService;
import com.restaurant.labor.employee.Employee;
import com.restaurant.labor.employee.EmployeeRepository;
import com.restaurant.labor.employee.EmployeeStatus;
import com.restaurant.labor.employee.Permission;
import com.restaurant.labor.labor.LaborDtos.PunchRequest;
import com.restaurant.labor.labor.LaborDtos.Warning;
import com.restaurant.labor.labor.LaborDtos.WeekToDate;
import com.restaurant.labor.labor.payroll.OvertimeCalculator;
import com.restaurant.labor.labor.payroll.PayPeriodGuard;
import com.restaurant.labor.labor.schedule.Schedule;
import com.restaurant.labor.labor.schedule.ScheduleRepository;
import com.restaurant.labor.labor.schedule.ScheduledShift;
import com.restaurant.labor.labor.schedule.ScheduledShiftRepository;
import com.restaurant.labor.labor.timecard.TimecardService;
import com.restaurant.labor.labor.timecard.TimecardState;
import com.restaurant.labor.location.BusinessDates;
import com.restaurant.labor.location.Location;
import com.restaurant.labor.location.LocationRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Records clock-in, break and clock-out punches. This is the busiest call in the system (every shift change)
 * and the one with the most edge cases: double taps, offline terminals, buddy punching, early clock-ins,
 * forgotten clock-outs and minors' curfews.
 */
@Service
public class PunchService {

    /** Device clock may differ from server clock by this much before we flag it (online punches only). */
    static final Duration CLOCK_DRIFT_TOLERANCE = Duration.ofMinutes(5);
    /** Small allowance for device clocks running slightly ahead. */
    static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(2);
    /** Minors may not clock in before this local time either. */
    static final LocalTime MINOR_EARLIEST = LocalTime.of(5, 0);

    /** Result of recording a punch. {@code replayed=true}: same client_punch_id seen before, original returned. */
    public record Recorded(Punch punch, boolean replayed, WeekToDate weekToDate, List<Warning> warnings) {}

    private final PunchRepository punches;
    private final ClockStateRepository clockStates;
    private final EmployeeRepository employees;
    private final LocationRepository locations;
    private final ScheduledShiftRepository shifts;
    private final ScheduleRepository schedules;
    private final TimecardService timecards;
    private final PayPeriodGuard periodGuard;
    private final AuthorizationService authz;
    private final AuditService audit;

    public PunchService(PunchRepository punches, ClockStateRepository clockStates, EmployeeRepository employees,
                        LocationRepository locations, ScheduledShiftRepository shifts, ScheduleRepository schedules,
                        TimecardService timecards, PayPeriodGuard periodGuard, AuthorizationService authz,
                        AuditService audit) {
        this.punches = punches;
        this.clockStates = clockStates;
        this.employees = employees;
        this.locations = locations;
        this.shifts = shifts;
        this.schedules = schedules;
        this.timecards = timecards;
        this.periodGuard = periodGuard;
        this.authz = authz;
        this.audit = audit;
    }

    @Transactional
    public Recorded record(PunchRequest req) {
        Caller c = Caller.current();
        String employeeId = req.employeeId() != null ? req.employeeId() : c.employeeId();
        Instant now = Instant.now();
        Instant occurredAt = req.occurredAt() != null ? req.occurredAt().toInstant() : now;

        // 1. Idempotency: the device-generated client_punch_id makes retries and offline replays safe.
        var existing = punches.findByTenantIdAndClientPunchId(c.tenantId(), req.clientPunchId());
        if (existing.isPresent()) {
            Punch p = existing.get();
            boolean same = p.getEmployeeId().equals(employeeId) && p.getType() == req.type()
                    && p.getLocationId().equals(req.locationId())
                    && (req.occurredAt() == null || p.getOccurredAt().equals(occurredAt));
            if (!same) {
                throw ApiException.conflict("idempotency_key_reused",
                        "This client_punch_id was already used for a different punch.");
            }
            return new Recorded(p, true, null, List.of());
        }

        // 2. Who may punch for whom. Employees punch for themselves; anyone else is a manager override.
        Location location = locations.require(req.locationId(), c.tenantId());
        PunchSource source;
        if (employeeId.equals(c.employeeId())) {
            source = c.viaPinSession() ? PunchSource.POS : PunchSource.MOBILE;
        } else {
            authz.require(Permission.PUNCHES_WRITE_OTHERS, location.getId());
            source = PunchSource.MANAGER;
        }

        // 3. The employee must be active and work at this location.
        Employee employee = employees.require(employeeId, c.tenantId());
        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw ApiException.unprocessable("employee_not_active", "Only active employees can punch.");
        }
        if (!authz.isAssignedAt(c.tenantId(), employeeId, location.getId())) {
            throw ApiException.unprocessable("not_assigned_to_location", "This employee does not work at this location.");
        }
        if (occurredAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw ApiException.unprocessable("future_punch", "occurred_at is in the future.");
        }

        // 4. State check under a per-employee lock (two terminals can't race).
        ClockState state = clockStates.lockForUpdate(employeeId, c.tenantId())
                .orElseGet(() -> clockStates.saveAndFlush(new ClockState(employeeId, c.tenantId())));
        checkTransition(state, req.type(), location.getId());
        if (state.getLastPunchAt() != null && occurredAt.isBefore(state.getLastPunchAt())) {
            throw ApiException.unprocessable("punch_out_of_order",
                    "This punch is earlier than the employee's previous punch. Offline punches must be sent in device-time order.")
                    .with("last_punch_at", state.getLastPunchAt());
        }

        LocalDate businessDate = req.type() == PunchType.CLOCK_IN
                ? BusinessDates.of(occurredAt, location)
                : state.getOpenBusinessDate();

        // 5. Frozen pay periods: a very late offline sync can't change already-locked hours.
        periodGuard.assertOpen(c.tenantId(), location.getId(), businessDate);

        List<String> flags = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        ScheduledShift shift = null;

        if (req.type() == PunchType.CLOCK_IN) {
            if (req.jobCode() == null || req.jobCode().isBlank()) {
                throw ApiException.unprocessable("job_code_required", "job_code is required to clock in.");
            }
            shift = findScheduledShift(c.tenantId(), employeeId, location.getId(), occurredAt, req.scheduledShiftId());
            if (shift == null) {
                flags.add("unscheduled");
            } else {
                Instant earliest = shift.getStartAt().minus(Duration.ofMinutes(location.getEarlyClockInGraceMinutes()));
                if (occurredAt.isBefore(earliest) && source != PunchSource.MANAGER) {
                    throw ApiException.unprocessable("early_clock_in",
                                    "Too early to clock in for this shift. Ask a manager to clock you in.")
                            .with("scheduled_start", shift.getStartAt())
                            .with("earliest_allowed", earliest);
                }
            }
        }
        if (employee.isMinor() && (req.type() == PunchType.CLOCK_IN || req.type() == PunchType.BREAK_END)) {
            LocalTime local = occurredAt.atZone(location.zone()).toLocalTime();
            if (!local.isBefore(location.getMinorCurfew()) || local.isBefore(MINOR_EARLIEST)) {
                throw ApiException.unprocessable("labor_rule_violation",
                        "Employees under 18 cannot work at this time at this location.")
                        .with("rule", "minor_curfew").with("curfew", location.getMinorCurfew());
            }
        }
        boolean offline = Boolean.TRUE.equals(req.offline());
        if (!offline && Duration.between(occurredAt, now).abs().compareTo(CLOCK_DRIFT_TOLERANCE) > 0) {
            flags.add("clock_drift");
        }
        if (offline) flags.add("offline_sync");
        if (source == PunchSource.MANAGER) flags.add("manager_override");

        String jobCode = req.type() == PunchType.CLOCK_IN ? req.jobCode() : state.getOpenJobCode();
        Punch punch = punches.save(new Punch(Ids.next("pch"), c.tenantId(), employeeId, location.getId(), req.type(),
                occurredAt, source, c.employeeId(), req.deviceId(), jobCode, req.clientPunchId(),
                shift == null ? null : shift.getId(), businessDate, String.join(",", flags)));

        if (req.type() == PunchType.CLOCK_IN) state.clockIn(punch);
        else state.apply(req.type(), occurredAt);

        TimecardState tc = timecards.ensure(c.tenantId(), employeeId, location.getId(), businessDate);
        tc.changed();
        if (req.type() == PunchType.CLOCK_OUT) tc.clearReview();

        if (source == PunchSource.MANAGER) {
            audit.record("punch.manager_override", "punch", punch.getId(), req.type() + " for " + employeeId);
        }

        WeekToDate wtd = weekToDate(c.tenantId(), employeeId, businessDate);
        if (req.type() == PunchType.CLOCK_IN) {
            long planned = shift != null ? shift.minutes() : 8 * 60;
            long remaining = OvertimeCalculator.WEEKLY_REGULAR_LIMIT_MINUTES - wtd.workedMinutes();
            if (remaining < planned) {
                warnings.add(new Warning("approaching_overtime", String.format(
                        "%s will reach 40 hours this week after %dh%02dm.", employee.getName(),
                        Math.max(0, remaining) / 60, Math.max(0, remaining) % 60)));
            }
        }
        return new Recorded(punch, false, wtd, warnings);
    }

    /** Who's working right now at a location. */
    @Transactional(readOnly = true)
    public List<ClockState> onTheClock(String locationId) {
        Caller c = Caller.current();
        locations.require(locationId, c.tenantId());
        authz.require(Permission.TIMECARDS_READ, locationId);
        return clockStates.findByTenantIdAndOpenLocationIdAndStateNot(c.tenantId(), locationId, ClockState.State.OFF)
                .stream().sorted(Comparator.comparing(ClockState::getOpenClockInAt)).toList();
    }

    @Transactional(readOnly = true)
    public CursorPage<Punch> list(String employeeId, String locationId, LocalDate from, LocalDate to,
                                  String cursor, Integer limit) {
        Caller c = Caller.current();
        if (employeeId == null && locationId == null) {
            throw new IllegalArgumentException("Pass employee_id or location_id.");
        }
        if (employeeId != null && !employeeId.equals(c.employeeId())) {
            if (locationId != null) authz.require(Permission.TIMECARDS_READ, locationId);
            else authz.requireOverEmployee(Permission.TIMECARDS_READ, employeeId);
        } else if (employeeId == null) {
            authz.require(Permission.TIMECARDS_READ, locationId);
        }
        int n = CursorPage.clampLimit(limit);
        List<Punch> rows = punches.page(c.tenantId(), employeeId, locationId, from, to, CursorPage.decode(cursor), Limit.of(n + 1));
        return CursorPage.of(rows, n, Punch::getId, p -> p);
    }

    public WeekToDate weekToDate(String tenantId, String employeeId, LocalDate businessDate) {
        LocalDate weekStart = BusinessDates.weekStart(businessDate);
        long worked = timecards.workByDay(tenantId, employeeId, weekStart, businessDate).values().stream()
                .mapToLong(TimecardService.DayWork::workedMinutes).sum();
        return new WeekToDate(worked, OvertimeCalculator.WEEKLY_REGULAR_LIMIT_MINUTES);
    }

    // ------------------------------------------------------------------------------------------

    private static void checkTransition(ClockState s, PunchType type, String locationId) {
        switch (type) {
            case CLOCK_IN -> {
                if (s.getState() != ClockState.State.OFF) {
                    throw ApiException.conflict("already_clocked_in", "This employee is already clocked in.")
                            .with("open_punch_id", s.getOpenClockInPunchId())
                            .with("clocked_in_at", s.getOpenClockInAt())
                            .with("open_location_id", s.getOpenLocationId());
                }
            }
            case BREAK_START -> {
                if (s.getState() != ClockState.State.ON) {
                    throw ApiException.conflict(s.getState() == ClockState.State.OFF ? "not_clocked_in" : "already_on_break",
                            "Can't start a break in the current state (" + s.getState() + ").");
                }
            }
            case BREAK_END -> {
                if (s.getState() != ClockState.State.ON_BREAK) {
                    throw ApiException.conflict("break_not_started", "There is no break in progress.");
                }
            }
            case CLOCK_OUT -> {
                if (s.getState() == ClockState.State.OFF) {
                    throw ApiException.conflict("not_clocked_in", "This employee is not clocked in.");
                }
            }
        }
        if (type != PunchType.CLOCK_IN && !Objects.equals(s.getOpenLocationId(), locationId)) {
            throw ApiException.unprocessable("wrong_location", "The open shift is at another location.")
                    .with("open_location_id", s.getOpenLocationId());
        }
    }

    /** Uses the given shift id, or finds the published shift whose start is nearest to the clock-in (within 3h). */
    private ScheduledShift findScheduledShift(String tenantId, String employeeId, String locationId, Instant at, String shiftId) {
        if (shiftId != null) {
            ScheduledShift s = shifts.require(shiftId, tenantId);
            if (!s.getEmployeeId().equals(employeeId) || !s.getLocationId().equals(locationId)) {
                throw ApiException.unprocessable("shift_mismatch", "That shift is not this employee's shift at this location.");
            }
            return s;
        }
        Duration window = Duration.ofHours(3);
        return shifts.findForEmployeeBetween(tenantId, employeeId, at.minus(window), at.plus(window)).stream()
                .filter(s -> s.getLocationId().equals(locationId))
                .filter(s -> schedules.findById(s.getScheduleId()).map(sc -> sc.getStatus() == Schedule.Status.PUBLISHED).orElse(false))
                .min(Comparator.comparing(s -> Duration.between(s.getStartAt(), at).abs()))
                .orElse(null);
    }
}

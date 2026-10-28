package com.restaurant.labor.labor.schedule;

import com.restaurant.labor.common.ApiException;
import com.restaurant.labor.common.AuditService;
import com.restaurant.labor.common.Caller;
import com.restaurant.labor.common.ETags;
import com.restaurant.labor.common.Ids;
import com.restaurant.labor.employee.AuthorizationService;
import com.restaurant.labor.employee.EmployeeRepository;
import com.restaurant.labor.employee.EmployeeStatus;
import com.restaurant.labor.employee.Permission;
import com.restaurant.labor.labor.LaborDtos.CreateShiftRequest;
import com.restaurant.labor.labor.LaborDtos.UpdateShiftRequest;
import com.restaurant.labor.location.BusinessDates;
import com.restaurant.labor.location.Location;
import com.restaurant.labor.location.LocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class ScheduleService {

    static final Duration MAX_SHIFT = Duration.ofHours(16);

    private final ScheduleRepository schedules;
    private final ScheduledShiftRepository shifts;
    private final LocationRepository locations;
    private final EmployeeRepository employees;
    private final AuthorizationService authz;
    private final AuditService audit;

    public ScheduleService(ScheduleRepository schedules, ScheduledShiftRepository shifts, LocationRepository locations,
                           EmployeeRepository employees, AuthorizationService authz, AuditService audit) {
        this.schedules = schedules;
        this.shifts = shifts;
        this.locations = locations;
        this.employees = employees;
        this.authz = authz;
        this.audit = audit;
    }

    @Transactional
    public Schedule create(String locationId, LocalDate weekStart) {
        Caller c = Caller.current();
        locations.require(locationId, c.tenantId());
        authz.require(Permission.SCHEDULES_MANAGE, locationId);
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw ApiException.unprocessable("invalid_week_start", "week_start must be a Monday.");
        }
        schedules.findByTenantIdAndLocationIdAndWeekStart(c.tenantId(), locationId, weekStart).ifPresent(s -> {
            throw ApiException.conflict("schedule_exists", "A schedule for this week already exists.")
                    .with("schedule_id", s.getId());
        });
        return schedules.save(new Schedule(Ids.next("sch"), c.tenantId(), locationId, weekStart, c.employeeId()));
    }

    @Transactional(readOnly = true)
    public Schedule get(String scheduleId) {
        Caller c = Caller.current();
        Schedule s = schedules.require(scheduleId, c.tenantId());
        if (s.getStatus() == Schedule.Status.DRAFT) {
            authz.require(Permission.SCHEDULES_MANAGE, s.getLocationId());
        } else if (!authz.isAssignedAt(c.tenantId(), c.employeeId(), s.getLocationId())) {
            throw ApiException.notFound("Schedule");
        }
        return s;
    }

    public List<ScheduledShift> shiftsOf(Schedule s) {
        return shifts.findByTenantIdAndScheduleIdOrderByStartAtAsc(s.getTenantId(), s.getId());
    }

    @Transactional
    public ScheduledShift addShift(String scheduleId, CreateShiftRequest req) {
        Caller c = Caller.current();
        Schedule s = schedules.require(scheduleId, c.tenantId());
        authz.require(Permission.SCHEDULES_MANAGE, s.getLocationId());
        Instant start = req.startAt().toInstant();
        Instant end = req.endAt().toInstant();
        validateShift(s, req.employeeId(), start, end, null);
        ScheduledShift shift = new ScheduledShift(Ids.next("shf"), c.tenantId(), s.getId(), s.getLocationId(),
                req.employeeId(), req.jobCode(), start, end, req.notes());
        if (s.getStatus() == Schedule.Status.PUBLISHED) {
            shift.markChangedAfterPublish();
            audit.record("shift.added_after_publish", "shift", shift.getId(), "employee=" + req.employeeId());
        }
        return shifts.save(shift);
    }

    @Transactional
    public ScheduledShift updateShift(String scheduleId, String shiftId, UpdateShiftRequest req, String ifMatch) {
        Caller c = Caller.current();
        Schedule s = schedules.require(scheduleId, c.tenantId());
        authz.require(Permission.SCHEDULES_MANAGE, s.getLocationId());
        ScheduledShift shift = shifts.require(shiftId, c.tenantId());
        if (!shift.getScheduleId().equals(s.getId())) throw ApiException.notFound("Shift");
        ETags.requireMatch(ifMatch, shift.getVersion());

        String employeeId = req.employeeId() != null ? req.employeeId() : shift.getEmployeeId();
        Instant start = req.startAt() != null ? req.startAt().toInstant() : shift.getStartAt();
        Instant end = req.endAt() != null ? req.endAt().toInstant() : shift.getEndAt();
        validateShift(s, employeeId, start, end, shift.getId());

        shift.setEmployeeId(employeeId);
        shift.setStartAt(start);
        shift.setEndAt(end);
        if (req.jobCode() != null) shift.setJobCode(req.jobCode());
        if (req.notes() != null) shift.setNotes(req.notes());
        if (s.getStatus() == Schedule.Status.PUBLISHED) {
            // Predictive-scheduling laws can require premium pay for late changes, so keep a trail.
            shift.markChangedAfterPublish();
            audit.record("shift.changed_after_publish", "shift", shift.getId(), "employee=" + employeeId);
        }
        return shifts.saveAndFlush(shift);
    }

    @Transactional
    public void deleteShift(String scheduleId, String shiftId) {
        Caller c = Caller.current();
        Schedule s = schedules.require(scheduleId, c.tenantId());
        authz.require(Permission.SCHEDULES_MANAGE, s.getLocationId());
        shifts.findByIdAndTenantId(shiftId, c.tenantId())
                .filter(sh -> sh.getScheduleId().equals(s.getId()))
                .ifPresent(sh -> {
                    if (s.getStatus() == Schedule.Status.PUBLISHED) {
                        audit.record("shift.removed_after_publish", "shift", sh.getId(), "employee=" + sh.getEmployeeId());
                    }
                    shifts.delete(sh);
                });
        // Deleting a shift that is already gone is a no-op (DELETE is idempotent).
    }

    /** DRAFT -> PUBLISHED. Publishing again is a no-op. A real system would notify employees here. */
    @Transactional
    public Schedule publish(String scheduleId) {
        Caller c = Caller.current();
        Schedule s = schedules.require(scheduleId, c.tenantId());
        authz.require(Permission.SCHEDULES_MANAGE, s.getLocationId());
        if (s.getStatus() == Schedule.Status.PUBLISHED) return s;
        s.publish(c.employeeId());
        audit.record("schedule.published", "schedule", s.getId(), "week_start=" + s.getWeekStart());
        return s;
    }

    /** An employee's shifts between two instants. Employees see published schedules only. */
    @Transactional(readOnly = true)
    public List<ScheduledShift> shiftsForEmployee(String employeeId, Instant from, Instant to) {
        Caller c = Caller.current();
        employees.require(employeeId, c.tenantId());
        boolean self = employeeId.equals(c.employeeId());
        if (!self) {
            authz.requireOverEmployee(Permission.SCHEDULES_MANAGE, employeeId);
        }
        return shifts.findForEmployeeBetween(c.tenantId(), employeeId, from, to).stream()
                .filter(sh -> !self || schedules.findById(sh.getScheduleId())
                        .map(sc -> sc.getStatus() == Schedule.Status.PUBLISHED).orElse(false))
                .toList();
    }

    void validateShift(Schedule s, String employeeId, Instant start, Instant end, String ignoreShiftId) {
        Caller c = Caller.current();
        if (!end.isAfter(start)) {
            throw ApiException.unprocessable("invalid_shift_times", "end_at must be after start_at.");
        }
        if (Duration.between(start, end).compareTo(MAX_SHIFT) > 0) {
            throw ApiException.unprocessable("shift_too_long", "A shift can be at most 16 hours.");
        }
        Location loc = locations.require(s.getLocationId(), c.tenantId());
        LocalDate day = BusinessDates.of(start, loc);
        if (day.isBefore(s.getWeekStart()) || day.isAfter(s.weekEnd())) {
            throw ApiException.unprocessable("outside_schedule_week", "The shift must start within the schedule's week.");
        }
        var employee = employees.require(employeeId, c.tenantId());
        if (employee.getStatus() == EmployeeStatus.TERMINATED || employee.getStatus() == EmployeeStatus.SUSPENDED) {
            throw ApiException.unprocessable("employee_not_active", "Cannot schedule an inactive employee.");
        }
        if (!authz.isAssignedAt(c.tenantId(), employeeId, s.getLocationId())) {
            throw ApiException.unprocessable("not_assigned_to_location", "The employee does not work at this location.");
        }
        List<ScheduledShift> clashes = shifts.findForEmployeeBetween(c.tenantId(), employeeId, start, end).stream()
                .filter(sh -> !sh.getId().equals(ignoreShiftId)).toList();
        if (!clashes.isEmpty()) {
            throw ApiException.conflict("shift_overlap", "The employee already has a shift at that time.")
                    .with("conflicting_shift_ids", clashes.stream().map(ScheduledShift::getId).toList());
        }
    }
}

package com.restaurant.labor.labor.requests;

import com.restaurant.labor.common.ApiException;
import com.restaurant.labor.common.AuditService;
import com.restaurant.labor.common.Caller;
import com.restaurant.labor.common.Ids;
import com.restaurant.labor.employee.AuthorizationService;
import com.restaurant.labor.employee.EmployeeRepository;
import com.restaurant.labor.employee.EmployeeStatus;
import com.restaurant.labor.employee.Permission;
import com.restaurant.labor.labor.LaborDtos.SwapOfferRequest;
import com.restaurant.labor.labor.LaborDtos.TimeOffCreateRequest;
import com.restaurant.labor.labor.schedule.ScheduledShift;
import com.restaurant.labor.labor.schedule.ScheduledShiftRepository;
import com.restaurant.labor.location.Location;
import com.restaurant.labor.location.LocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Time-off requests and shift swaps: employee asks, manager decides. */
@Service
public class RequestsService {

    public record TimeOffDecision(TimeOffRequest request, List<String> conflictingShiftIds) {}

    private static final EnumSet<ShiftSwapRequest.Status> OPEN_SWAPS =
            EnumSet.of(ShiftSwapRequest.Status.OFFERED, ShiftSwapRequest.Status.PENDING_APPROVAL);

    private final TimeOffRequestRepository timeOff;
    private final ShiftSwapRequestRepository swaps;
    private final ScheduledShiftRepository shifts;
    private final EmployeeRepository employees;
    private final LocationRepository locations;
    private final AuthorizationService authz;
    private final AuditService audit;

    public RequestsService(TimeOffRequestRepository timeOff, ShiftSwapRequestRepository swaps,
                           ScheduledShiftRepository shifts, EmployeeRepository employees, LocationRepository locations,
                           AuthorizationService authz, AuditService audit) {
        this.timeOff = timeOff;
        this.swaps = swaps;
        this.shifts = shifts;
        this.employees = employees;
        this.locations = locations;
        this.authz = authz;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ time off

    @Transactional
    public TimeOffRequest requestTimeOff(String employeeId, TimeOffCreateRequest req) {
        Caller c = Caller.current();
        if (!employeeId.equals(c.employeeId())) {
            throw ApiException.forbidden("not_self", "Employees request their own time off.");
        }
        if (req.endDate().isBefore(req.startDate())) {
            throw ApiException.unprocessable("invalid_dates", "end_date must not be before start_date.");
        }
        return timeOff.save(new TimeOffRequest(Ids.next("tor"), c.tenantId(), employeeId, req.startDate(), req.endDate(), req.reason()));
    }

    @Transactional(readOnly = true)
    public List<TimeOffRequest> timeOffOf(String employeeId) {
        Caller c = Caller.current();
        employees.require(employeeId, c.tenantId());
        if (!employeeId.equals(c.employeeId())) authz.requireOverEmployee(Permission.REQUESTS_APPROVE, employeeId);
        return timeOff.findByTenantIdAndEmployeeIdOrderByStartDateDesc(c.tenantId(), employeeId);
    }

    /** Approve or deny. Approval also reports any scheduled shifts that now need covering. */
    @Transactional
    public TimeOffDecision decideTimeOff(String requestId, boolean approve, String note) {
        Caller c = Caller.current();
        TimeOffRequest r = timeOff.require(requestId, c.tenantId());
        if (r.getEmployeeId().equals(c.employeeId())) {
            throw ApiException.forbidden("self_approval_denied", "You cannot decide your own request.");
        }
        authz.requireOverEmployee(Permission.REQUESTS_APPROVE, r.getEmployeeId());
        TimeOffRequest.Status target = approve ? TimeOffRequest.Status.APPROVED : TimeOffRequest.Status.DENIED;
        if (r.getStatus() == target) return new TimeOffDecision(r, conflicts(r)); // idempotent
        if (r.getStatus() != TimeOffRequest.Status.REQUESTED) {
            throw ApiException.conflict("invalid_state", "This request was already " + r.getStatus().name().toLowerCase() + ".");
        }
        r.decide(target, c.employeeId(), note);
        audit.record("time_off." + target.name().toLowerCase(), "time_off_request", r.getId(), null);
        return new TimeOffDecision(r, approve ? conflicts(r) : List.of());
    }

    public List<String> conflicts(TimeOffRequest r) {
        // Wide UTC window around the local dates; precise enough to point a manager at shifts to re-cover.
        Instant from = r.getStartDate().atStartOfDay().minusHours(14).toInstant(java.time.ZoneOffset.UTC);
        Instant to = r.getEndDate().atTime(LocalTime.MAX).plusHours(14).toInstant(java.time.ZoneOffset.UTC);
        return shifts.findForEmployeeBetween(r.getTenantId(), r.getEmployeeId(), from, to).stream()
                .filter(s -> {
                    Location loc = locations.findById(s.getLocationId()).orElse(null);
                    if (loc == null) return true;
                    var d = s.getStartAt().atZone(loc.zone()).toLocalDate();
                    return !d.isBefore(r.getStartDate()) && !d.isAfter(r.getEndDate());
                })
                .map(ScheduledShift::getId).toList();
    }

    // ------------------------------------------------------------------ shift swaps

    @Transactional
    public ShiftSwapRequest offerShift(String shiftId, SwapOfferRequest req) {
        Caller c = Caller.current();
        ScheduledShift shift = shifts.require(shiftId, c.tenantId());
        if (!shift.getEmployeeId().equals(c.employeeId())) {
            throw ApiException.forbidden("not_your_shift", "You can only offer your own shifts.");
        }
        if (!shift.getStartAt().isAfter(Instant.now())) {
            throw ApiException.unprocessable("shift_started", "This shift has already started.");
        }
        if (!swaps.findByTenantIdAndShiftIdAndStatusIn(c.tenantId(), shiftId, OPEN_SWAPS).isEmpty()) {
            throw ApiException.conflict("swap_already_open", "This shift already has an open swap request.");
        }
        if (req != null && req.pickupEmployeeId() != null) {
            checkCanTake(shift, req.pickupEmployeeId());
        }
        return swaps.save(new ShiftSwapRequest(Ids.next("swp"), c.tenantId(), shiftId, shift.getLocationId(),
                c.employeeId(), req == null ? null : req.pickupEmployeeId(), req == null ? null : req.note()));
    }

    /** A colleague picks up an open offer. */
    @Transactional
    public ShiftSwapRequest pickUp(String swapId) {
        Caller c = Caller.current();
        ShiftSwapRequest s = swaps.require(swapId, c.tenantId());
        if (s.getStatus() != ShiftSwapRequest.Status.OFFERED) {
            throw ApiException.conflict("invalid_state", "This offer is no longer open.");
        }
        if (s.getOfferedBy().equals(c.employeeId())) {
            throw ApiException.unprocessable("own_offer", "You cannot pick up your own shift.");
        }
        checkCanTake(shifts.require(s.getShiftId(), c.tenantId()), c.employeeId());
        s.pickUp(c.employeeId());
        return s;
    }

    /** Manager approval moves the shift to the new employee. */
    @Transactional
    public ShiftSwapRequest decideSwap(String swapId, boolean approve) {
        Caller c = Caller.current();
        ShiftSwapRequest s = swaps.require(swapId, c.tenantId());
        authz.require(Permission.REQUESTS_APPROVE, s.getLocationId());
        ShiftSwapRequest.Status target = approve ? ShiftSwapRequest.Status.APPROVED : ShiftSwapRequest.Status.DENIED;
        if (s.getStatus() == target) return s; // idempotent
        if (approve && s.getStatus() != ShiftSwapRequest.Status.PENDING_APPROVAL) {
            throw ApiException.conflict("invalid_state", "Only a swap with someone picking it up can be approved.");
        }
        if (!OPEN_SWAPS.contains(s.getStatus())) {
            throw ApiException.conflict("invalid_state", "This swap was already decided.");
        }
        if (approve) {
            ScheduledShift shift = shifts.require(s.getShiftId(), c.tenantId());
            checkCanTake(shift, s.getPickupEmployeeId());
            shift.setEmployeeId(s.getPickupEmployeeId());
            shift.markChangedAfterPublish();
        }
        s.decide(target, c.employeeId());
        audit.record("shift_swap." + target.name().toLowerCase(), "shift", s.getShiftId(),
                "from=" + s.getOfferedBy() + ", to=" + s.getPickupEmployeeId());
        return s;
    }

    @Transactional(readOnly = true)
    public List<ShiftSwapRequest> openSwapsAt(String locationId) {
        Caller c = Caller.current();
        locations.require(locationId, c.tenantId());
        if (!authz.isAssignedAt(c.tenantId(), c.employeeId(), locationId)) {
            throw ApiException.notFound("Location");
        }
        return swaps.findByTenantIdAndLocationIdAndStatusInOrderByCreatedAtAsc(c.tenantId(), locationId, OPEN_SWAPS);
    }

    private void checkCanTake(ScheduledShift shift, String employeeId) {
        Caller c = Caller.current();
        var e = employees.require(employeeId, c.tenantId());
        if (e.getStatus() != EmployeeStatus.ACTIVE) {
            throw ApiException.unprocessable("employee_not_active", "That employee is not active.");
        }
        if (!authz.isAssignedAt(c.tenantId(), employeeId, shift.getLocationId())) {
            throw ApiException.unprocessable("not_assigned_to_location", "That employee does not work at this location.");
        }
        List<String> clashes = new ArrayList<>();
        shifts.findForEmployeeBetween(c.tenantId(), employeeId, shift.getStartAt(), shift.getEndAt())
                .forEach(x -> clashes.add(x.getId()));
        if (!clashes.isEmpty()) {
            throw ApiException.conflict("shift_overlap", "That employee already has a shift at that time.")
                    .with("conflicting_shift_ids", clashes);
        }
    }
}

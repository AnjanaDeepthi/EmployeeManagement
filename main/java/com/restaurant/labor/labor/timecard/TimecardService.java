package com.restaurant.labor.labor.timecard;

import com.restaurant.labor.common.ApiException;
import com.restaurant.labor.common.AuditService;
import com.restaurant.labor.common.Caller;
import com.restaurant.labor.common.ETags;
import com.restaurant.labor.common.Ids;
import com.restaurant.labor.employee.AuthorizationService;
import com.restaurant.labor.employee.Permission;
import com.restaurant.labor.labor.LaborDtos.AdjustmentRequest;
import com.restaurant.labor.labor.payroll.PayPeriodGuard;
import com.restaurant.labor.labor.punch.ClockStateRepository;
import com.restaurant.labor.labor.punch.Punch;
import com.restaurant.labor.labor.punch.PunchRepository;
import com.restaurant.labor.labor.punch.PunchType;
import com.restaurant.labor.labor.timecard.TimecardCalculator.EffectivePunch;
import com.restaurant.labor.location.Location;
import com.restaurant.labor.location.LocationRepository;
import com.restaurant.labor.location.RuleSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
public class TimecardService {

    /** One computed timecard: workflow state + the effective punches + the calculated hours. */
    public record View(TimecardState state, List<EffectivePunch> punches, Set<String> adjustedPunchIds,
                       List<TimecardAdjustment> adjustments, TimecardCalculator.Result result) {}

    /** Worked minutes and main job code for one day, used by payroll. */
    public record DayWork(long workedMinutes, String jobCode, boolean open) {}

    private final TimecardStateRepository timecards;
    private final TimecardAdjustmentRepository adjustments;
    private final PunchRepository punches;
    private final ClockStateRepository clockStates;
    private final LocationRepository locations;
    private final PayPeriodGuard periodGuard;
    private final AuthorizationService authz;
    private final AuditService audit;

    public TimecardService(TimecardStateRepository timecards, TimecardAdjustmentRepository adjustments,
                           PunchRepository punches, ClockStateRepository clockStates, LocationRepository locations,
                           PayPeriodGuard periodGuard, AuthorizationService authz, AuditService audit) {
        this.timecards = timecards;
        this.adjustments = adjustments;
        this.punches = punches;
        this.clockStates = clockStates;
        this.locations = locations;
        this.periodGuard = periodGuard;
        this.authz = authz;
        this.audit = audit;
    }

    /** Returns the timecard row for (employee, business date), creating it on the first punch. */
    @Transactional
    public TimecardState ensure(String tenantId, String employeeId, String locationId, LocalDate businessDate) {
        return timecards.findByIdAndTenantId(TimecardState.idFor(employeeId, businessDate), tenantId)
                .orElseGet(() -> timecards.save(new TimecardState(tenantId, employeeId, locationId, businessDate)));
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public View get(String timecardId) {
        Caller c = Caller.current();
        TimecardState tc = timecards.require(timecardId, c.tenantId());
        if (!tc.getEmployeeId().equals(c.employeeId())) {
            authz.require(Permission.TIMECARDS_READ, tc.getLocationId());
        }
        return compute(tc);
    }

    @Transactional(readOnly = true)
    public List<View> list(String locationId, LocalDate from, LocalDate to, TimecardState.Status status) {
        Caller c = Caller.current();
        locations.require(locationId, c.tenantId());
        authz.require(Permission.TIMECARDS_READ, locationId);
        return timecards.findByTenantIdAndLocationIdAndBusinessDateBetweenOrderByBusinessDateAscEmployeeIdAsc(
                        c.tenantId(), locationId, from, to).stream()
                .filter(tc -> status == null || tc.getStatus() == status)
                .map(this::compute)
                .toList();
    }

    public View compute(TimecardState tc) {
        List<TimecardAdjustment> adj = adjustments.findByTenantIdAndTimecardIdOrderByCreatedAtAsc(tc.getTenantId(), tc.getId());
        List<Punch> raw = punches.findByTenantIdAndEmployeeIdAndBusinessDateOrderByOccurredAtAsc(
                tc.getTenantId(), tc.getEmployeeId(), tc.getBusinessDate());
        Set<String> adjusted = new java.util.HashSet<>();
        List<EffectivePunch> effective = applyAdjustments(raw, adj, adjusted);
        Location loc = locations.require(tc.getLocationId(), tc.getTenantId());
        TimecardCalculator.Result result = TimecardCalculator.calculate(effective, Instant.now(),
                loc.getRuleSet() == RuleSet.US_CALIFORNIA);
        return new View(tc, effective, adjusted, adj, result);
    }

    /** Worked minutes per business date for one employee (for weekly overtime and payroll). */
    public Map<LocalDate, DayWork> workByDay(String tenantId, String employeeId, LocalDate from, LocalDate to) {
        Map<LocalDate, DayWork> out = new TreeMap<>();
        for (TimecardState tc : timecards.findByTenantIdAndEmployeeIdAndBusinessDateBetweenOrderByBusinessDateAsc(
                tenantId, employeeId, from, to)) {
            TimecardCalculator.Result r = compute(tc).result();
            out.put(tc.getBusinessDate(), new DayWork(r.workedMinutes(), r.primaryJobCode(), r.open()));
        }
        return out;
    }

    // ------------------------------------------------------------------ writes

    /**
     * A manager's correction. Punches are never edited: the adjustment is stored next to them, with reason
     * and actor, and the timecard is recomputed. An approved timecard goes back to OPEN and needs re-approval.
     */
    @Transactional
    public View adjust(String timecardId, AdjustmentRequest req, String ifMatch) {
        Caller c = Caller.current();
        TimecardState tc = timecards.require(timecardId, c.tenantId());
        authz.require(Permission.TIMECARDS_ADJUST, tc.getLocationId());
        if (tc.getEmployeeId().equals(c.employeeId())) {
            throw ApiException.forbidden("self_adjustment_denied", "You cannot adjust your own timecard. Ask another manager.");
        }
        periodGuard.assertOpen(c.tenantId(), tc.getLocationId(), tc.getBusinessDate());
        ETags.requireMatch(ifMatch, tc.getVersion());

        View before = compute(tc);
        Instant newTime = req.occurredAt() == null ? null : req.occurredAt().toInstant();
        Instant previous = null;
        switch (req.action()) {
            case ADD_PUNCH -> {
                if (req.punchType() == null || newTime == null) {
                    throw ApiException.unprocessable("invalid_adjustment", "ADD_PUNCH needs punch_type and occurred_at.");
                }
            }
            case MOVE_PUNCH, VOID_PUNCH -> {
                if (req.punchId() == null) {
                    throw ApiException.unprocessable("invalid_adjustment", req.action() + " needs punch_id.");
                }
                EffectivePunch target = before.punches().stream().filter(p -> p.punchId().equals(req.punchId())).findFirst()
                        .orElseThrow(() -> ApiException.unprocessable("unknown_punch", "That punch is not on this timecard."));
                previous = target.at();
                if (req.action() == TimecardAdjustment.Action.MOVE_PUNCH && newTime == null) {
                    throw ApiException.unprocessable("invalid_adjustment", "MOVE_PUNCH needs occurred_at.");
                }
            }
        }
        if (newTime != null && newTime.isAfter(Instant.now())) {
            throw ApiException.unprocessable("future_punch", "Corrected times cannot be in the future.");
        }

        TimecardAdjustment adj = new TimecardAdjustment(Ids.next("adj"), c.tenantId(), tc.getId(), tc.getEmployeeId(),
                tc.getBusinessDate(), req.action(), req.punchId(), req.punchType(), newTime, previous, req.jobCode(),
                req.reasonCode(), req.note(), c.employeeId());

        // Validate the result before saving: the corrected punch sequence must make sense.
        List<TimecardAdjustment> all = new ArrayList<>(before.adjustments());
        all.add(adj);
        List<Punch> raw = punches.findByTenantIdAndEmployeeIdAndBusinessDateOrderByOccurredAtAsc(
                c.tenantId(), tc.getEmployeeId(), tc.getBusinessDate());
        List<EffectivePunch> after = applyAdjustments(raw, all, new java.util.HashSet<>());
        TimecardCalculator.Result check = TimecardCalculator.calculate(after, Instant.now(), false);
        if (check.flags().contains("sequence_error")) {
            throw ApiException.unprocessable("invalid_sequence",
                    "After this change the punches would be out of order (e.g. two clock-ins in a row).");
        }

        adjustments.save(adj);
        tc.changed();
        if (!check.open()) {
            tc.clearReview();
            // If this closed the employee's open shift (a forgotten clock-out), the clock state must say OFF.
            clockStates.findByEmployeeIdAndTenantId(tc.getEmployeeId(), c.tenantId())
                    .filter(cs -> tc.getBusinessDate().equals(cs.getOpenBusinessDate()))
                    .ifPresent(cs -> cs.close());
        }
        timecards.saveAndFlush(tc);
        audit.record("timecard.adjusted", "timecard", tc.getId(),
                req.action() + " reason=" + req.reasonCode() + (req.punchId() == null ? "" : " punch=" + req.punchId()));
        return compute(tc);
    }

    /** Approve. Separation of duties: managers can't approve their own timecard. */
    @Transactional
    public View approve(String timecardId, String ifMatch) {
        Caller c = Caller.current();
        TimecardState tc = timecards.require(timecardId, c.tenantId());
        authz.require(Permission.TIMECARDS_APPROVE, tc.getLocationId());
        if (tc.getEmployeeId().equals(c.employeeId())) {
            throw ApiException.forbidden("self_approval_denied", "You cannot approve your own timecard.");
        }
        if (ifMatch != null) ETags.requireMatch(ifMatch, tc.getVersion());
        if (tc.getStatus() == TimecardState.Status.LOCKED) {
            throw new ApiException(HttpStatus.LOCKED, "pay_period_locked", "This timecard is in a locked pay period.");
        }
        View v = compute(tc);
        if (tc.getStatus() == TimecardState.Status.APPROVED) return v; // idempotent
        if (v.result().open()) {
            throw ApiException.unprocessable("timecard_open",
                    "The shift has no clock-out yet. Add the missing clock-out as an adjustment first.");
        }
        if (v.result().flags().contains("sequence_error")) {
            throw ApiException.unprocessable("invalid_sequence", "Fix the punch sequence before approving.");
        }
        tc.approve(c.employeeId());
        timecards.saveAndFlush(tc);
        audit.record("timecard.approved", "timecard", tc.getId(), "worked_minutes=" + v.result().workedMinutes());
        return compute(tc);
    }

    // ------------------------------------------------------------------ helpers

    /** Applies VOID, MOVE and ADD adjustments (in order) to the raw punches. */
    static List<EffectivePunch> applyAdjustments(List<Punch> raw, List<TimecardAdjustment> adj, Set<String> adjustedIds) {
        Map<String, EffectivePunch> byId = new LinkedHashMap<>();
        for (Punch p : raw) {
            byId.put(p.getId(), new EffectivePunch(p.getId(), p.getType(), p.getOccurredAt(), p.getJobCode()));
        }
        for (TimecardAdjustment a : adj) {
            switch (a.getAction()) {
                case VOID_PUNCH -> {
                    byId.remove(a.getPunchId());
                    adjustedIds.add(a.getPunchId());
                }
                case MOVE_PUNCH -> {
                    EffectivePunch p = byId.get(a.getPunchId());
                    if (p != null) {
                        byId.put(p.punchId(), new EffectivePunch(p.punchId(), p.type(), a.getOccurredAt(),
                                a.getJobCode() != null ? a.getJobCode() : p.jobCode()));
                        adjustedIds.add(p.punchId());
                    }
                }
                case ADD_PUNCH -> {
                    byId.put(a.getId(), new EffectivePunch(a.getId(), a.getPunchType(), a.getOccurredAt(), a.getJobCode()));
                    adjustedIds.add(a.getId());
                }
            }
        }
        List<EffectivePunch> out = new ArrayList<>(byId.values());
        out.sort(java.util.Comparator.comparing(EffectivePunch::at));
        return out;
    }
}

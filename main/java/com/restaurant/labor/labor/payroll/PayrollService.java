package com.restaurant.labor.labor.payroll;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.labor.common.ApiException;
import com.restaurant.labor.common.AuditService;
import com.restaurant.labor.common.Caller;
import com.restaurant.labor.common.Ids;
import com.restaurant.labor.employee.AuthorizationService;
import com.restaurant.labor.employee.Employee;
import com.restaurant.labor.employee.EmployeeRepository;
import com.restaurant.labor.employee.Permission;
import com.restaurant.labor.labor.LaborDtos.DayLine;
import com.restaurant.labor.labor.LaborDtos.EmployeePaySummary;
import com.restaurant.labor.labor.LaborDtos.ExportRequest;
import com.restaurant.labor.labor.LaborDtos.PayPeriodSummary;
import com.restaurant.labor.labor.LaborDtos.PayRateRequest;
import com.restaurant.labor.labor.LaborDtos.PayTotals;
import com.restaurant.labor.labor.LaborDtos.PriorPeriodAdjustmentRequest;
import com.restaurant.labor.labor.LaborDtos.TipDeclarationRequest;
import com.restaurant.labor.labor.timecard.TimecardService;
import com.restaurant.labor.labor.timecard.TimecardState;
import com.restaurant.labor.labor.timecard.TimecardStateRepository;
import com.restaurant.labor.location.BusinessDates;
import com.restaurant.labor.location.Location;
import com.restaurant.labor.location.LocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

@Service
public class PayrollService {

    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    private static final BigDecimal OT_MULTIPLIER = new BigDecimal("1.5");
    private static final BigDecimal DT_MULTIPLIER = new BigDecimal("2");

    /** {@code created=false}: the Idempotency-Key was seen before; the original export is returned. */
    public record ExportResult(PayrollExport export, boolean created) {}

    private final PayRateRepository rates;
    private final PayPeriodRepository periods;
    private final PriorPeriodAdjustmentRepository priorAdjustments;
    private final TipDeclarationRepository tips;
    private final PayrollExportRepository exports;
    private final TimecardStateRepository timecardStates;
    private final TimecardService timecards;
    private final EmployeeRepository employees;
    private final LocationRepository locations;
    private final PayPeriodGuard periodGuard;
    private final AuthorizationService authz;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public PayrollService(PayRateRepository rates, PayPeriodRepository periods,
                          PriorPeriodAdjustmentRepository priorAdjustments, TipDeclarationRepository tips,
                          PayrollExportRepository exports, TimecardStateRepository timecardStates,
                          TimecardService timecards, EmployeeRepository employees, LocationRepository locations,
                          PayPeriodGuard periodGuard, AuthorizationService authz, AuditService audit,
                          ObjectMapper objectMapper) {
        this.rates = rates;
        this.periods = periods;
        this.priorAdjustments = priorAdjustments;
        this.tips = tips;
        this.exports = exports;
        this.timecardStates = timecardStates;
        this.timecards = timecards;
        this.employees = employees;
        this.locations = locations;
        this.periodGuard = periodGuard;
        this.authz = authz;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ pay rates (sensitive)

    @Transactional(readOnly = true)
    public List<PayRate> ratesOf(String employeeId) {
        Caller c = Caller.current();
        employees.require(employeeId, c.tenantId());
        if (!employeeId.equals(c.employeeId())) {
            authz.requireOverEmployee(Permission.PAYROLL_READ, employeeId);
        }
        return rates.findByTenantIdAndEmployeeIdOrderByJobCodeAscEffectiveFromAsc(c.tenantId(), employeeId);
    }

    /** Adds a new effective-dated rate; the previous open-ended rate for that job ends the day before. */
    @Transactional
    public PayRate addRate(String employeeId, PayRateRequest req) {
        Caller c = Caller.current();
        employees.require(employeeId, c.tenantId());
        authz.require(Permission.PAY_RATES_MANAGE, null);
        List<PayRate> history = rates.findByTenantIdAndEmployeeIdAndJobCodeOrderByEffectiveFromAsc(
                c.tenantId(), employeeId, req.jobCode());
        if (!history.isEmpty()) {
            PayRate latest = history.get(history.size() - 1);
            if (!req.effectiveFrom().isAfter(latest.getEffectiveFrom())) {
                throw ApiException.unprocessable("invalid_effective_date",
                        "effective_from must be after the current rate's effective_from (" + latest.getEffectiveFrom() + ").");
            }
            latest.endOn(req.effectiveFrom().minusDays(1));
        }
        PayRate rate = rates.save(new PayRate(Ids.next("rate"), c.tenantId(), employeeId, req.jobCode(),
                req.hourlyCents(), req.effectiveFrom(), c.employeeId()));
        audit.record("pay_rate.added", "employee", employeeId, "job=" + req.jobCode() + ", from=" + req.effectiveFrom());
        return rate;
    }

    // ------------------------------------------------------------------ tips

    @Transactional
    public TipDeclaration declareTips(String employeeId, TipDeclarationRequest req) {
        Caller c = Caller.current();
        if (!employeeId.equals(c.employeeId())) {
            throw ApiException.forbidden("not_self", "Employees declare their own tips.");
        }
        locations.require(req.locationId(), c.tenantId());
        if (!authz.isAssignedAt(c.tenantId(), employeeId, req.locationId())) {
            throw ApiException.unprocessable("not_assigned_to_location", "You do not work at this location.");
        }
        periodGuard.assertOpen(c.tenantId(), req.locationId(), req.businessDate());
        return tips.save(new TipDeclaration(Ids.next("tip"), c.tenantId(), employeeId, req.locationId(),
                req.businessDate(), req.cashTipsCents()));
    }

    // ------------------------------------------------------------------ pay periods

    @Transactional
    public PayPeriod createPeriod(String locationId, LocalDate start, LocalDate end) {
        Caller c = Caller.current();
        locations.require(locationId, c.tenantId());
        authz.require(Permission.PAY_PERIODS_MANAGE, locationId);
        if (end.isBefore(start)) {
            throw ApiException.unprocessable("invalid_dates", "end_date must not be before start_date.");
        }
        List<PayPeriod> overlap = periods.findOverlapping(c.tenantId(), locationId, start, end);
        if (!overlap.isEmpty()) {
            throw ApiException.conflict("pay_period_overlap", "Pay periods at a location cannot overlap.")
                    .with("overlapping_ids", overlap.stream().map(PayPeriod::getId).toList());
        }
        return periods.save(new PayPeriod(Ids.next("pp"), c.tenantId(), locationId, start, end));
    }

    @Transactional(readOnly = true)
    public List<PayPeriod> listPeriods(String locationId, PayPeriod.Status status) {
        Caller c = Caller.current();
        if (locationId != null) authz.require(Permission.TIMECARDS_READ, locationId);
        else authz.require(Permission.TIMECARDS_READ, null);
        return periods.search(c.tenantId(), locationId, status);
    }

    @Transactional(readOnly = true)
    public PayPeriod getPeriod(String id) {
        Caller c = Caller.current();
        PayPeriod p = periods.require(id, c.tenantId());
        authz.require(Permission.TIMECARDS_READ, p.getLocationId());
        return p;
    }

    /**
     * Lock: every timecard in the period must be approved. After this, nothing in the period changes;
     * late corrections become prior-period adjustments in the next period. Locking twice is a no-op.
     */
    @Transactional
    public PayPeriod lock(String periodId) {
        Caller c = Caller.current();
        PayPeriod p = periods.require(periodId, c.tenantId());
        authz.require(Permission.PAY_PERIODS_MANAGE, p.getLocationId());
        if (p.isClosed()) return p;
        List<TimecardState> cards = timecardStates.findByTenantIdAndLocationIdAndBusinessDateBetweenOrderByBusinessDateAscEmployeeIdAsc(
                c.tenantId(), p.getLocationId(), p.getStartDate(), p.getEndDate());
        List<String> unapproved = cards.stream()
                .filter(tc -> tc.getStatus() != TimecardState.Status.APPROVED)
                .map(TimecardState::getId).toList();
        if (!unapproved.isEmpty()) {
            throw ApiException.unprocessable("unapproved_timecards", "Approve every timecard before locking.")
                    .with("timecard_ids", unapproved);
        }
        cards.forEach(TimecardState::lock);
        p.lock(c.employeeId());
        audit.record("pay_period.locked", "pay_period", p.getId(), p.getStartDate() + ".." + p.getEndDate());
        return p;
    }

    /** A correction for a date in a locked period, paid in this (open) period. */
    @Transactional
    public PriorPeriodAdjustment addPriorPeriodAdjustment(String openPeriodId, PriorPeriodAdjustmentRequest req) {
        Caller c = Caller.current();
        PayPeriod target = periods.require(openPeriodId, c.tenantId());
        authz.require(Permission.TIMECARDS_ADJUST, target.getLocationId());
        if (target.isClosed()) {
            throw ApiException.unprocessable("pay_period_not_open", "Prior-period adjustments go into an open pay period.");
        }
        if (req.employeeId().equals(c.employeeId())) {
            throw ApiException.forbidden("self_adjustment_denied", "You cannot adjust your own hours.");
        }
        if (req.minutesDelta() == 0) {
            throw ApiException.unprocessable("invalid_adjustment", "minutes_delta must not be zero.");
        }
        employees.require(req.employeeId(), c.tenantId());
        PayPeriod original = periods.findCovering(c.tenantId(), target.getLocationId(), req.originalBusinessDate())
                .filter(PayPeriod::isClosed)
                .orElseThrow(() -> ApiException.unprocessable("original_period_not_locked",
                        "original_business_date must be in a locked pay period. Otherwise adjust the timecard directly."));
        PriorPeriodAdjustment adj = priorAdjustments.save(new PriorPeriodAdjustment(Ids.next("ppa"), c.tenantId(),
                req.employeeId(), target.getLocationId(), target.getId(), req.originalBusinessDate(), req.minutesDelta(),
                req.reasonCode(), req.note(), c.employeeId()));
        audit.record("prior_period_adjustment.added", "employee", req.employeeId(),
                "original_period=" + original.getId() + ", date=" + req.originalBusinessDate() + ", minutes=" + req.minutesDelta());
        return adj;
    }

    @Transactional(readOnly = true)
    public List<PriorPeriodAdjustment> priorAdjustmentsOf(String periodId) {
        Caller c = Caller.current();
        PayPeriod p = periods.require(periodId, c.tenantId());
        authz.require(Permission.TIMECARDS_READ, p.getLocationId());
        return priorAdjustments.findByTenantIdAndPayPeriodIdOrderByCreatedAtAsc(c.tenantId(), periodId);
    }

    // ------------------------------------------------------------------ summary

    /** Per-employee regular, overtime and double-time minutes, gross wages and tips. Needs payroll:read. */
    @Transactional(readOnly = true)
    public PayPeriodSummary summary(String periodId) {
        Caller c = Caller.current();
        PayPeriod p = periods.require(periodId, c.tenantId());
        authz.require(Permission.PAYROLL_READ, p.getLocationId());
        return buildSummary(p);
    }

    PayPeriodSummary buildSummary(PayPeriod p) {
        String tenantId = p.getTenantId();
        Location location = locations.require(p.getLocationId(), tenantId);

        // Weekly overtime needs whole workweeks, including days just before the period starts.
        LocalDate weekFrom = BusinessDates.weekStart(p.getStartDate());
        LocalDate weekTo = BusinessDates.weekStart(p.getEndDate()).plusDays(6);

        TreeSet<String> employeeIds = new TreeSet<>();
        timecardStates.findByTenantIdAndLocationIdAndBusinessDateBetweenOrderByBusinessDateAscEmployeeIdAsc(
                tenantId, p.getLocationId(), p.getStartDate(), p.getEndDate()).forEach(tc -> employeeIds.add(tc.getEmployeeId()));
        List<PriorPeriodAdjustment> prior = priorAdjustments.findByTenantIdAndPayPeriodIdOrderByCreatedAtAsc(tenantId, p.getId());
        prior.forEach(a -> employeeIds.add(a.getEmployeeId()));
        Map<String, Long> tipsByEmployee = new TreeMap<>();
        tips.findByTenantIdAndLocationIdAndBusinessDateBetween(tenantId, p.getLocationId(), p.getStartDate(), p.getEndDate())
                .forEach(t -> {
                    employeeIds.add(t.getEmployeeId());
                    tipsByEmployee.merge(t.getEmployeeId(), t.getCashTipsCents(), Long::sum);
                });

        List<EmployeePaySummary> lines = new ArrayList<>();
        long tReg = 0, tOt = 0, tDt = 0, tGross = 0, tTips = 0;
        for (String employeeId : employeeIds) {
            Employee e = employees.findByIdAndTenantId(employeeId, tenantId).orElse(null);
            List<PayRate> empRates = rates.findByTenantIdAndEmployeeIdOrderByJobCodeAscEffectiveFromAsc(tenantId, employeeId);
            Map<LocalDate, TimecardService.DayWork> work = timecards.workByDay(tenantId, employeeId, weekFrom, weekTo);
            Map<LocalDate, Long> minutes = new LinkedHashMap<>();
            work.forEach((d, w) -> minutes.put(d, w.workedMinutes()));
            Map<LocalDate, OvertimeCalculator.Split> splits = OvertimeCalculator.split(location.getRuleSet(), minutes);

            List<DayLine> days = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            long reg = 0, ot = 0, dt = 0;
            BigDecimal gross = BigDecimal.ZERO;
            for (var entry : splits.entrySet()) {
                LocalDate day = entry.getKey();
                if (!p.contains(day)) continue;
                OvertimeCalculator.Split s = entry.getValue();
                String job = work.get(day).jobCode();
                Long rate = rateOn(empRates, job, day).map(PayRate::getHourlyCents).orElse(null);
                if (rate == null && s.total() > 0) warnings.add("missing_pay_rate:" + job + ":" + day);
                if (work.get(day).open()) warnings.add("open_shift:" + day);
                reg += s.regular();
                ot += s.overtime();
                dt += s.doubleTime();
                if (rate != null) gross = gross.add(wages(rate, s));
                days.add(new DayLine(day, job, s.regular(), s.overtime(), s.doubleTime(), rate));
            }
            long priorMinutes = 0;
            for (PriorPeriodAdjustment a : prior) {
                if (!a.getEmployeeId().equals(employeeId)) continue;
                priorMinutes += a.getMinutesDelta();
                // Paid at the regular rate in effect on the original date (first job with a rate that day).
                Optional<PayRate> r = empRates.stream().filter(x -> x.appliesOn(a.getOriginalBusinessDate())).findFirst();
                if (r.isPresent()) {
                    gross = gross.add(BigDecimal.valueOf(r.get().getHourlyCents()).multiply(BigDecimal.valueOf(a.getMinutesDelta()))
                            .divide(SIXTY, 2, RoundingMode.HALF_UP));
                } else {
                    warnings.add("missing_pay_rate_for_prior_adjustment:" + a.getOriginalBusinessDate());
                }
            }
            long grossCents = gross.setScale(0, RoundingMode.HALF_UP).longValueExact();
            long tipCents = tipsByEmployee.getOrDefault(employeeId, 0L);
            lines.add(new EmployeePaySummary(employeeId, e == null ? null : e.getName(), reg, ot, dt, priorMinutes,
                    grossCents, tipCents, days, warnings));
            tReg += reg; tOt += ot; tDt += dt; tGross += grossCents; tTips += tipCents;
        }
        return new PayPeriodSummary(p.getId(), p.getLocationId(), p.getStartDate(), p.getEndDate(), p.getStatus().name(),
                location.getRuleSet().name(), lines, new PayTotals(tReg, tOt, tDt, tGross, tTips));
    }

    private static BigDecimal wages(long hourlyCents, OvertimeCalculator.Split s) {
        BigDecimal perMinute = BigDecimal.valueOf(hourlyCents).divide(SIXTY, 6, RoundingMode.HALF_UP);
        return perMinute.multiply(BigDecimal.valueOf(s.regular()))
                .add(perMinute.multiply(OT_MULTIPLIER).multiply(BigDecimal.valueOf(s.overtime())))
                .add(perMinute.multiply(DT_MULTIPLIER).multiply(BigDecimal.valueOf(s.doubleTime())));
    }

    private static Optional<PayRate> rateOn(List<PayRate> empRates, String jobCode, LocalDate day) {
        return empRates.stream().filter(r -> r.getJobCode().equals(jobCode) && r.appliesOn(day)).findFirst();
    }

    // ------------------------------------------------------------------ export

    /**
     * Export a locked period to the payroll provider, exactly once:
     * <ul>
     *   <li>the same Idempotency-Key returns the original export;</li>
     *   <li>at most one SUCCEEDED export per (period, provider);</li>
     *   <li>a re-export must name the export it supersedes.</li>
     * </ul>
     * For the demo the "provider call" is synchronous; in production this would be an async job (202 + status).
     */
    @Transactional
    public ExportResult export(String periodId, ExportRequest req, String idempotencyKey) {
        Caller c = Caller.current();
        PayPeriod p = periods.require(periodId, c.tenantId());
        authz.require(Permission.PAYROLL_EXPORT, null);

        if (idempotencyKey != null) {
            var seen = exports.findByTenantIdAndPayPeriodIdAndIdempotencyKey(c.tenantId(), periodId, idempotencyKey);
            if (seen.isPresent()) {
                if (!seen.get().getProvider().equals(req.provider())) {
                    throw ApiException.conflict("idempotency_key_reused", "This Idempotency-Key was used for a different export.");
                }
                return new ExportResult(seen.get(), false);
            }
        }
        if (!p.isClosed()) {
            throw ApiException.conflict("pay_period_not_locked", "Lock the pay period before exporting.");
        }
        var current = exports.findByTenantIdAndPayPeriodIdAndProviderAndStatus(
                c.tenantId(), periodId, req.provider(), PayrollExport.Status.SUCCEEDED);
        if (current.isPresent()) {
            if (req.supersedes() == null) {
                throw ApiException.conflict("export_exists",
                                "This period was already exported to " + req.provider() + ". To re-export, pass supersedes.")
                        .with("export_id", current.get().getId());
            }
            if (!req.supersedes().equals(current.get().getId())) {
                throw ApiException.conflict("supersedes_mismatch", "supersedes must be the current export's id.")
                        .with("current_export_id", current.get().getId());
            }
            current.get().supersede();
        } else if (req.supersedes() != null) {
            throw ApiException.unprocessable("nothing_to_supersede", "There is no previous export to supersede.");
        }

        PayPeriodSummary summary = buildSummary(p);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String checksum = sha256(payload);
        String confirmation = "prov_" + req.provider() + "_" + checksum.substring(0, 12);
        PayrollExport export = exports.save(new PayrollExport(Ids.next("pxe"), c.tenantId(), periodId, req.provider(),
                req.supersedes(), idempotencyKey, checksum, confirmation, summary.employees().size(),
                summary.totals().grossWagesCents(), payload, c.employeeId()));
        p.markExported();
        audit.record("payroll.exported", "pay_period", periodId,
                "export=" + export.getId() + ", provider=" + req.provider()
                        + (req.supersedes() == null ? "" : ", supersedes=" + req.supersedes()));
        return new ExportResult(export, true);
    }

    @Transactional(readOnly = true)
    public List<PayrollExport> exportsOf(String periodId) {
        Caller c = Caller.current();
        periods.require(periodId, c.tenantId());
        authz.require(Permission.PAYROLL_READ, null);
        return exports.findByTenantIdAndPayPeriodIdOrderByCreatedAtAsc(c.tenantId(), periodId);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

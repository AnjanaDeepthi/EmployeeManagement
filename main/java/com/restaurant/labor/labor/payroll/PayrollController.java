package com.restaurant.labor.labor.payroll;

import com.restaurant.labor.employee.EmployeeService;
import com.restaurant.labor.labor.LaborDtos.CreatePayPeriodRequest;
import com.restaurant.labor.labor.LaborDtos.ExportRequest;
import com.restaurant.labor.labor.LaborDtos.ExportResponse;
import com.restaurant.labor.labor.LaborDtos.PayPeriodResponse;
import com.restaurant.labor.labor.LaborDtos.PayPeriodSummary;
import com.restaurant.labor.labor.LaborDtos.PayRateRequest;
import com.restaurant.labor.labor.LaborDtos.PayRateResponse;
import com.restaurant.labor.labor.LaborDtos.PriorPeriodAdjustmentRequest;
import com.restaurant.labor.labor.LaborDtos.PriorPeriodAdjustmentResponse;
import com.restaurant.labor.labor.LaborDtos.TipDeclarationRequest;
import com.restaurant.labor.labor.LaborDtos.TipDeclarationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@Tag(name = "9. Tips, pay rates and payroll")
@RestController
public class PayrollController {

    private final PayrollService service;

    public PayrollController(PayrollService service) {
        this.service = service;
    }

    // ---- pay rates

    @Operation(summary = "Effective-dated pay rates (payroll:read, or your own)")
    @GetMapping("/v1/employees/{employeeId}/pay_rates")
    public List<PayRateResponse> rates(@PathVariable String employeeId) {
        return service.ratesOf(EmployeeService.resolve(employeeId)).stream().map(PayrollController::toResponse).toList();
    }

    @Operation(summary = "Add a pay rate; the previous rate for that job ends the day before (pay_rates:manage)")
    @PostMapping("/v1/employees/{employeeId}/pay_rates")
    @ResponseStatus(HttpStatus.CREATED)
    public PayRateResponse addRate(@PathVariable String employeeId, @Valid @RequestBody PayRateRequest req) {
        return toResponse(service.addRate(employeeId, req));
    }

    // ---- tips

    @Operation(summary = "Declare your cash tips for a business date")
    @PostMapping("/v1/employees/{employeeId}/tip_declarations")
    @ResponseStatus(HttpStatus.CREATED)
    public TipDeclarationResponse declareTips(@PathVariable String employeeId, @Valid @RequestBody TipDeclarationRequest req) {
        TipDeclaration t = service.declareTips(EmployeeService.resolve(employeeId), req);
        return new TipDeclarationResponse(t.getId(), t.getEmployeeId(), t.getLocationId(), t.getBusinessDate(),
                t.getCashTipsCents(), t.getDeclaredAt());
    }

    // ---- pay periods

    @Operation(summary = "Create a pay period at a location (no overlaps)")
    @PostMapping("/v1/locations/{locationId}/pay_periods")
    public ResponseEntity<PayPeriodResponse> createPeriod(@PathVariable String locationId,
                                                          @Valid @RequestBody CreatePayPeriodRequest req) {
        PayPeriod p = service.createPeriod(locationId, req.startDate(), req.endDate());
        return ResponseEntity.created(URI.create("/v1/pay_periods/" + p.getId())).body(toResponse(p));
    }

    @Operation(summary = "List pay periods")
    @GetMapping("/v1/pay_periods")
    public List<PayPeriodResponse> listPeriods(@RequestParam(name = "location_id", required = false) String locationId,
                                               @RequestParam(required = false) PayPeriod.Status status) {
        return service.listPeriods(locationId, status).stream().map(PayrollController::toResponse).toList();
    }

    @GetMapping("/v1/pay_periods/{periodId}")
    public PayPeriodResponse getPeriod(@PathVariable String periodId) {
        return toResponse(service.getPeriod(periodId));
    }

    @Operation(summary = "Lock the period: all timecards must be approved; no edits afterwards (idempotent)")
    @PostMapping("/v1/pay_periods/{periodId}/lock")
    public PayPeriodResponse lock(@PathVariable String periodId) {
        return toResponse(service.lock(periodId));
    }

    @Operation(summary = "Regular / overtime / double-time minutes, gross wages and tips per employee (payroll:read)")
    @GetMapping("/v1/pay_periods/{periodId}/summary")
    public PayPeriodSummary summary(@PathVariable String periodId) {
        return service.summary(periodId);
    }

    @Operation(summary = "Correction for a date in a locked period, paid in this open period")
    @PostMapping("/v1/pay_periods/{periodId}/prior_period_adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    public PriorPeriodAdjustmentResponse addPriorAdjustment(@PathVariable String periodId,
                                                            @Valid @RequestBody PriorPeriodAdjustmentRequest req) {
        return toResponse(service.addPriorPeriodAdjustment(periodId, req));
    }

    @GetMapping("/v1/pay_periods/{periodId}/prior_period_adjustments")
    public List<PriorPeriodAdjustmentResponse> priorAdjustments(@PathVariable String periodId) {
        return service.priorAdjustmentsOf(periodId).stream().map(PayrollController::toResponse).toList();
    }

    // ---- exports

    @Operation(summary = "Export a locked period to a payroll provider, exactly once (Idempotency-Key supported)")
    @PostMapping("/v1/pay_periods/{periodId}/exports")
    public ResponseEntity<ExportResponse> export(@PathVariable String periodId,
                                                 @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                                 @Valid @RequestBody ExportRequest req) {
        PayrollService.ExportResult r = service.export(periodId, req, idempotencyKey);
        ExportResponse body = toResponse(r.export());
        if (!r.created()) return ResponseEntity.ok(body);
        return ResponseEntity.created(URI.create("/v1/pay_periods/" + periodId + "/exports/" + r.export().getId())).body(body);
    }

    @GetMapping("/v1/pay_periods/{periodId}/exports")
    public List<ExportResponse> exports(@PathVariable String periodId) {
        return service.exportsOf(periodId).stream().map(PayrollController::toResponse).toList();
    }

    // ---- mappers

    static PayRateResponse toResponse(PayRate r) {
        return new PayRateResponse(r.getId(), r.getEmployeeId(), r.getJobCode(), r.getHourlyCents(),
                r.getEffectiveFrom(), r.getEffectiveTo());
    }

    static PayPeriodResponse toResponse(PayPeriod p) {
        return new PayPeriodResponse(p.getId(), p.getLocationId(), p.getStartDate(), p.getEndDate(),
                p.getStatus().name(), p.getLockedBy(), p.getLockedAt());
    }

    static PriorPeriodAdjustmentResponse toResponse(PriorPeriodAdjustment a) {
        return new PriorPeriodAdjustmentResponse(a.getId(), a.getEmployeeId(), a.getPayPeriodId(),
                a.getOriginalBusinessDate(), a.getMinutesDelta(), a.getReasonCode(), a.getNote(), a.getActorId(),
                a.getCreatedAt());
    }

    static ExportResponse toResponse(PayrollExport e) {
        return new ExportResponse(e.getId(), e.getPayPeriodId(), e.getProvider(), e.getStatus().name(),
                e.getSupersedesId(), e.getChecksum(), e.getProviderConfirmationId(), e.getEmployeeCount(),
                e.getGrossWagesCents(), e.getCreatedBy(), e.getCreatedAt());
    }
}

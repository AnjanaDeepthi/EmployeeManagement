package com.restaurant.labor.labor.timecard;

import com.restaurant.labor.common.ApiException;
import com.restaurant.labor.common.Caller;
import com.restaurant.labor.common.ETags;
import com.restaurant.labor.labor.LaborDtos.AdjustmentDto;
import com.restaurant.labor.labor.LaborDtos.AdjustmentRequest;
import com.restaurant.labor.labor.LaborDtos.EffectivePunchDto;
import com.restaurant.labor.labor.LaborDtos.SegmentDto;
import com.restaurant.labor.labor.LaborDtos.TimecardResponse;
import com.restaurant.labor.labor.payroll.PayPeriod;
import com.restaurant.labor.labor.payroll.PayPeriodRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "8. Timecards")
@RestController
public class TimecardController {

    private final TimecardService service;
    private final PayPeriodRepository payPeriods;

    public TimecardController(TimecardService service, PayPeriodRepository payPeriods) {
        this.service = service;
        this.payPeriods = payPeriods;
    }

    @Operation(summary = "Timecards for review at a location: by pay_period_id, or by from/to business dates")
    @GetMapping("/v1/timecards")
    public List<TimecardResponse> list(@RequestParam(name = "location_id", required = false) String locationId,
                                       @RequestParam(name = "pay_period_id", required = false) String payPeriodId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(required = false) TimecardState.Status status) {
        if (payPeriodId != null) {
            PayPeriod p = payPeriods.require(payPeriodId, Caller.current().tenantId());
            locationId = p.getLocationId();
            from = p.getStartDate();
            to = p.getEndDate();
        }
        if (locationId == null || from == null || to == null) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "invalid_request",
                    "Pass pay_period_id, or location_id with from and to.");
        }
        return service.list(locationId, from, to, status).stream().map(TimecardController::toResponse).toList();
    }

    @Operation(summary = "One timecard: segments, totals, violations and the full adjustment history")
    @GetMapping("/v1/timecards/{timecardId}")
    public ResponseEntity<TimecardResponse> get(@PathVariable String timecardId) {
        TimecardService.View v = service.get(timecardId);
        return ResponseEntity.ok().eTag(ETags.of(v.state().getVersion())).body(toResponse(v));
    }

    @Operation(summary = "Correct a punch: ADD_PUNCH, MOVE_PUNCH or VOID_PUNCH, with reason (If-Match required)")
    @PostMapping("/v1/timecards/{timecardId}/adjustments")
    public ResponseEntity<TimecardResponse> adjust(@PathVariable String timecardId,
                                                   @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                   @Valid @RequestBody AdjustmentRequest req) {
        TimecardService.View v = service.adjust(timecardId, req, ifMatch);
        return ResponseEntity.ok().eTag(ETags.of(v.state().getVersion())).body(toResponse(v));
    }

    @Operation(summary = "Approve a timecard (not your own; If-Match optional)")
    @PostMapping("/v1/timecards/{timecardId}/approval")
    public ResponseEntity<TimecardResponse> approve(@PathVariable String timecardId,
                                                    @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        TimecardService.View v = service.approve(timecardId, ifMatch);
        return ResponseEntity.ok().eTag(ETags.of(v.state().getVersion())).body(toResponse(v));
    }

    static TimecardResponse toResponse(TimecardService.View v) {
        TimecardState s = v.state();
        var r = v.result();
        return new TimecardResponse(s.getId(), s.getEmployeeId(), s.getLocationId(), s.getBusinessDate(),
                s.getStatus().name(), s.getVersion(), r.workedMinutes(), r.breakMinutes(), r.open(), r.flags(),
                r.violations(), s.isNeedsReview(), s.getReviewReason(), s.getApprovedBy(), s.getApprovedAt(),
                r.segments().stream().map(seg -> new SegmentDto(seg.type(), seg.start(), seg.end(), seg.minutes(),
                        seg.jobCode(), seg.open())).toList(),
                v.punches().stream().map(p -> new EffectivePunchDto(p.punchId(), p.type(), p.at(), p.jobCode(),
                        v.adjustedPunchIds().contains(p.punchId()))).toList(),
                v.adjustments().stream().map(a -> new AdjustmentDto(a.getId(), a.getAction(), a.getPunchId(),
                        a.getPunchType(), a.getOccurredAt(), a.getPreviousOccurredAt(), a.getJobCode(),
                        a.getReasonCode(), a.getNote(), a.getActorId(), a.getCreatedAt())).toList());
    }
}

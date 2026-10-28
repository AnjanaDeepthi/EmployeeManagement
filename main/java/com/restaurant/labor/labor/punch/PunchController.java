package com.restaurant.labor.labor.punch;

import com.restaurant.labor.common.Caller;
import com.restaurant.labor.common.CursorPage;
import com.restaurant.labor.employee.EmployeeRepository;
import com.restaurant.labor.labor.LaborDtos.OnTheClockEntry;
import com.restaurant.labor.labor.LaborDtos.PunchRequest;
import com.restaurant.labor.labor.LaborDtos.PunchResponse;
import com.restaurant.labor.labor.timecard.TimecardState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@Tag(name = "7. Time and attendance")
@RestController
public class PunchController {

    private final PunchService service;
    private final EmployeeRepository employees;

    public PunchController(PunchService service, EmployeeRepository employees) {
        this.service = service;
        this.employees = employees;
    }

    @Operation(summary = "Clock in / out, start / end a break. Idempotent by client_punch_id.")
    @PostMapping("/v1/punches")
    public ResponseEntity<PunchResponse> punch(@Valid @RequestBody PunchRequest req) {
        PunchService.Recorded r = service.record(req);
        PunchResponse body = toResponse(r.punch(), r.weekToDate(), r.warnings());
        // A replay returns the original 201 response, marked so clients can tell.
        return ResponseEntity.created(URI.create("/v1/punches/" + r.punch().getId()))
                .header("Idempotent-Replayed", String.valueOf(r.replayed()))
                .body(body);
    }

    @Operation(summary = "List punches for an employee or a location between business dates (cursor paging)")
    @GetMapping("/v1/punches")
    public CursorPage<PunchResponse> list(@RequestParam(name = "employee_id", required = false) String employeeId,
                                          @RequestParam(name = "location_id", required = false) String locationId,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) Integer limit) {
        CursorPage<Punch> page = service.list(employeeId, locationId, from, to, cursor, limit);
        return new CursorPage<>(page.data().stream().map(p -> toResponse(p, null, null)).toList(),
                page.nextCursor(), page.hasMore());
    }

    @Operation(summary = "Who is on the clock right now at a location")
    @GetMapping("/v1/locations/{locationId}/on_the_clock")
    public List<OnTheClockEntry> onTheClock(@PathVariable String locationId) {
        String tenantId = Caller.current().tenantId();
        Instant now = Instant.now();
        return service.onTheClock(locationId).stream().map(cs -> new OnTheClockEntry(
                cs.getEmployeeId(),
                employees.findByIdAndTenantId(cs.getEmployeeId(), tenantId).map(e -> e.getName()).orElse(null),
                cs.getState().name(), cs.getOpenJobCode(), cs.getOpenClockInAt(),
                Duration.between(cs.getOpenClockInAt(), now).toMinutes(), cs.getOpenBusinessDate())).toList();
    }

    static PunchResponse toResponse(Punch p, com.restaurant.labor.labor.LaborDtos.WeekToDate wtd,
                                    List<com.restaurant.labor.labor.LaborDtos.Warning> warnings) {
        List<String> flags = p.getFlags() == null || p.getFlags().isBlank() ? List.of() : Arrays.asList(p.getFlags().split(","));
        return new PunchResponse(p.getId(), p.getEmployeeId(), p.getLocationId(), p.getType(), p.getOccurredAt(),
                p.getReceivedAt(), p.getBusinessDate(), p.getSource(), p.getRecordedBy(), p.getJobCode(),
                p.getScheduledShiftId(), TimecardState.idFor(p.getEmployeeId(), p.getBusinessDate()), flags, wtd, warnings);
    }
}

package com.restaurant.labor.labor.schedule;

import com.restaurant.labor.common.ETags;
import com.restaurant.labor.employee.EmployeeService;
import com.restaurant.labor.labor.LaborDtos.CreateScheduleRequest;
import com.restaurant.labor.labor.LaborDtos.CreateShiftRequest;
import com.restaurant.labor.labor.LaborDtos.ScheduleResponse;
import com.restaurant.labor.labor.LaborDtos.ShiftResponse;
import com.restaurant.labor.labor.LaborDtos.UpdateShiftRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

@Tag(name = "6. Scheduling")
@RestController
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
        this.service = service;
    }

    @Operation(summary = "Create a draft schedule for a week (week_start must be a Monday)")
    @PostMapping("/v1/locations/{locationId}/schedules")
    public ResponseEntity<ScheduleResponse> create(@PathVariable String locationId, @Valid @RequestBody CreateScheduleRequest req) {
        Schedule s = service.create(locationId, req.weekStart());
        return ResponseEntity.created(URI.create("/v1/schedules/" + s.getId())).body(toResponse(s));
    }

    @Operation(summary = "Get a schedule with its shifts (drafts need schedules:manage)")
    @GetMapping("/v1/schedules/{scheduleId}")
    public ScheduleResponse get(@PathVariable String scheduleId) {
        return toResponse(service.get(scheduleId));
    }

    @Operation(summary = "Add a shift (validates assignment, overlap, 16h max, week bounds)")
    @PostMapping("/v1/schedules/{scheduleId}/shifts")
    public ResponseEntity<ShiftResponse> addShift(@PathVariable String scheduleId, @Valid @RequestBody CreateShiftRequest req) {
        ScheduledShift sh = service.addShift(scheduleId, req);
        return ResponseEntity.created(URI.create("/v1/schedules/" + scheduleId + "/shifts/" + sh.getId()))
                .eTag(ETags.of(sh.getVersion())).body(toResponse(sh));
    }

    @Operation(summary = "Move or reassign a shift (If-Match required)")
    @PatchMapping("/v1/schedules/{scheduleId}/shifts/{shiftId}")
    public ResponseEntity<ShiftResponse> updateShift(@PathVariable String scheduleId, @PathVariable String shiftId,
                                                     @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                     @RequestBody UpdateShiftRequest req) {
        ScheduledShift sh = service.updateShift(scheduleId, shiftId, req, ifMatch);
        return ResponseEntity.ok().eTag(ETags.of(sh.getVersion())).body(toResponse(sh));
    }

    @Operation(summary = "Remove a shift (idempotent)")
    @DeleteMapping("/v1/schedules/{scheduleId}/shifts/{shiftId}")
    public ResponseEntity<Void> deleteShift(@PathVariable String scheduleId, @PathVariable String shiftId) {
        service.deleteShift(scheduleId, shiftId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Publish the schedule (idempotent)")
    @PostMapping("/v1/schedules/{scheduleId}/publication")
    public ScheduleResponse publish(@PathVariable String scheduleId) {
        return toResponse(service.publish(scheduleId));
    }

    @Operation(summary = "An employee's upcoming shifts ('me' supported)")
    @GetMapping("/v1/employees/{employeeId}/shifts")
    public List<ShiftResponse> employeeShifts(@PathVariable String employeeId,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return service.shiftsForEmployee(EmployeeService.resolve(employeeId), from.toInstant(), to.toInstant())
                .stream().map(ScheduleController::toResponse).toList();
    }

    private ScheduleResponse toResponse(Schedule s) {
        return new ScheduleResponse(s.getId(), s.getLocationId(), s.getWeekStart(), s.weekEnd(), s.getStatus().name(),
                s.getPublishedAt(), s.getPublishedBy(), s.getVersion(),
                service.shiftsOf(s).stream().map(ScheduleController::toResponse).toList());
    }

    static ShiftResponse toResponse(ScheduledShift sh) {
        return new ShiftResponse(sh.getId(), sh.getScheduleId(), sh.getLocationId(), sh.getEmployeeId(), sh.getJobCode(),
                sh.getStartAt(), sh.getEndAt(), sh.minutes(), sh.getNotes(), sh.isChangedAfterPublish(), sh.getVersion());
    }
}

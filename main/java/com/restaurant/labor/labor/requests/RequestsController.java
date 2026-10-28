package com.restaurant.labor.labor.requests;

import com.restaurant.labor.employee.EmployeeService;
import com.restaurant.labor.labor.LaborDtos.DecisionRequest;
import com.restaurant.labor.labor.LaborDtos.SwapOfferRequest;
import com.restaurant.labor.labor.LaborDtos.SwapResponse;
import com.restaurant.labor.labor.LaborDtos.TimeOffCreateRequest;
import com.restaurant.labor.labor.LaborDtos.TimeOffResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "10. Time off and shift swaps")
@RestController
public class RequestsController {

    private final RequestsService service;

    public RequestsController(RequestsService service) {
        this.service = service;
    }

    // ---- time off

    @Operation(summary = "Request time off (for yourself)")
    @PostMapping("/v1/employees/{employeeId}/time_off_requests")
    @ResponseStatus(HttpStatus.CREATED)
    public TimeOffResponse requestTimeOff(@PathVariable String employeeId, @Valid @RequestBody TimeOffCreateRequest req) {
        return toResponse(service.requestTimeOff(EmployeeService.resolve(employeeId), req), null);
    }

    @GetMapping("/v1/employees/{employeeId}/time_off_requests")
    public List<TimeOffResponse> listTimeOff(@PathVariable String employeeId) {
        return service.timeOffOf(EmployeeService.resolve(employeeId)).stream().map(r -> toResponse(r, null)).toList();
    }

    @Operation(summary = "Approve time off; the response lists shifts that now need covering")
    @PostMapping("/v1/time_off_requests/{requestId}/approval")
    public TimeOffResponse approveTimeOff(@PathVariable String requestId, @RequestBody(required = false) DecisionRequest req) {
        var d = service.decideTimeOff(requestId, true, req == null ? null : req.note());
        return toResponse(d.request(), d.conflictingShiftIds());
    }

    @PostMapping("/v1/time_off_requests/{requestId}/denial")
    public TimeOffResponse denyTimeOff(@PathVariable String requestId, @RequestBody(required = false) DecisionRequest req) {
        var d = service.decideTimeOff(requestId, false, req == null ? null : req.note());
        return toResponse(d.request(), null);
    }

    // ---- swaps

    @Operation(summary = "Offer your shift: name a colleague, or leave pickup_employee_id empty for an open offer")
    @PostMapping("/v1/shifts/{shiftId}/swap_requests")
    @ResponseStatus(HttpStatus.CREATED)
    public SwapResponse offer(@PathVariable String shiftId, @RequestBody(required = false) SwapOfferRequest req) {
        return toResponse(service.offerShift(shiftId, req));
    }

    @Operation(summary = "Pick up an open shift offer")
    @PostMapping("/v1/swap_requests/{swapId}/pickup")
    public SwapResponse pickUp(@PathVariable String swapId) {
        return toResponse(service.pickUp(swapId));
    }

    @Operation(summary = "Manager approves: the shift moves to the new employee")
    @PostMapping("/v1/swap_requests/{swapId}/approval")
    public SwapResponse approveSwap(@PathVariable String swapId) {
        return toResponse(service.decideSwap(swapId, true));
    }

    @PostMapping("/v1/swap_requests/{swapId}/denial")
    public SwapResponse denySwap(@PathVariable String swapId) {
        return toResponse(service.decideSwap(swapId, false));
    }

    @Operation(summary = "Open swap offers at a location")
    @GetMapping("/v1/locations/{locationId}/swap_requests")
    public List<SwapResponse> openSwaps(@PathVariable String locationId) {
        return service.openSwapsAt(locationId).stream().map(RequestsController::toResponse).toList();
    }

    static TimeOffResponse toResponse(TimeOffRequest r, List<String> conflicts) {
        return new TimeOffResponse(r.getId(), r.getEmployeeId(), r.getStartDate(), r.getEndDate(), r.getReason(),
                r.getStatus().name(), r.getDecidedBy(), r.getDecidedAt(), r.getDecisionNote(), conflicts);
    }

    static SwapResponse toResponse(ShiftSwapRequest s) {
        return new SwapResponse(s.getId(), s.getShiftId(), s.getLocationId(), s.getOfferedBy(), s.getPickupEmployeeId(),
                s.getStatus().name(), s.getNote(), s.getDecidedBy(), s.getDecidedAt());
    }
}

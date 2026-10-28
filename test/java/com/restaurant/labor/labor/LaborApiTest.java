package com.restaurant.labor.labor;

import com.fasterxml.jackson.databind.JsonNode;
import com.restaurant.labor.ApiTestSupport;
import com.restaurant.labor.labor.timecard.OpenShiftReviewJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LaborApiTest extends ApiTestSupport {

    @Autowired
    OpenShiftReviewJob openShiftReviewJob;

    private static Object punch(String clientId, String type, String job) {
        return map("client_punch_id", clientId, "location_id", "loc_deepellum", "type", type, "job_code", job);
    }

    // ------------------------------------------------------------------ punches

    @Test
    void clockInIsIdempotentAndFollowsTheStateMachine() throws Exception {
        String id = body(postAs("emp_maria", "/v1/punches", punch("dev1:0001", "CLOCK_IN", "line_cook"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.scheduled_shift_id").value("shf_maria_today"))
                .andExpect(jsonPath("$.source").value("MOBILE"))).get("id").asText();

        // Retrying the same tap (same client_punch_id) returns the original punch.
        postAs("emp_maria", "/v1/punches", punch("dev1:0001", "CLOCK_IN", "line_cook"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));

        // A new clock-in while already on the clock is a state conflict.
        postAs("emp_maria", "/v1/punches", punch("dev1:0002", "CLOCK_IN", "line_cook"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_clocked_in"))
                .andExpect(jsonPath("$.open_punch_id").value(id));

        postAs("emp_maria", "/v1/punches", punch("dev1:0003", "BREAK_END", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("break_not_started"));

        getAs("emp_lead_jordan", "/v1/locations/loc_deepellum/on_the_clock")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.employee_id == 'emp_maria')].state").value("ON"));

        postAs("emp_maria", "/v1/punches", punch("dev1:0004", "CLOCK_OUT", null)).andExpect(status().isCreated());
        postAs("emp_maria", "/v1/punches", punch("dev1:0005", "CLOCK_OUT", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_clocked_in"));
    }

    @Test
    void employeesCannotPunchForSomeoneElseButManagersCan() throws Exception {
        postAs("emp_marcus", "/v1/punches", map("client_punch_id", "dev1:0100", "employee_id", "emp_maria",
                "location_id", "loc_deepellum", "type", "CLOCK_IN", "job_code", "line_cook"))
                .andExpect(status().isForbidden());

        postAs("emp_lead_jordan", "/v1/punches", map("client_punch_id", "dev1:0101", "employee_id", "emp_maria",
                "location_id", "loc_deepellum", "type", "CLOCK_IN", "job_code", "line_cook"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("MANAGER"))
                .andExpect(jsonPath("$.recorded_by").value("emp_lead_jordan"));
    }

    @Test
    void earlyClockInIsRefusedWithoutAManager() throws Exception {
        // Sam's shift starts in 2 hours.
        postAs("emp_sam", "/v1/punches", punch("dev1:0200", "CLOCK_IN", "host"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("early_clock_in"));
    }

    @Test
    void forgottenClockOutIsFlaggedThenFixedByAdjustmentAndApproved() throws Exception {
        // Marcus clocked in 16 hours ago and never clocked out (seed data).
        assertThat(openShiftReviewJob.run()).isEqualTo(1);

        JsonNode open = body(getAs("emp_lead_jordan", "/v1/punches?employee_id=emp_marcus&location_id=loc_deepellum&from="
                + LocalDate.now().minusDays(3) + "&to=" + LocalDate.now().plusDays(2)));
        String clockInId = null;
        String timecardId = null;
        for (JsonNode p : open.get("data")) {
            if (p.get("id").asText().equals("pch_marcus_open")) {
                clockInId = p.get("id").asText();
                timecardId = p.get("timecard_id").asText();
            }
        }
        assertThat(clockInId).isNotNull();

        var tc = getAs("emp_lead_jordan", "/v1/timecards/" + timecardId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.needs_review").value(true));
        String etag = tc.andReturn().getResponse().getHeader("ETag");
        Instant clockIn = Instant.parse(body(tc).get("punches").get(0).get("at").asText());

        // Approving an open timecard is refused.
        postAs("emp_gm_tanya", "/v1/timecards/" + timecardId + "/approval", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("timecard_open"));

        // Adjustments need If-Match.
        Object fix = map("action", "ADD_PUNCH", "punch_type", "CLOCK_OUT",
                "occurred_at", clockIn.plus(7, ChronoUnit.HOURS).toString(), "reason_code", "forgot_clock_out");
        postAs("emp_lead_jordan", "/v1/timecards/" + timecardId + "/adjustments", fix)
                .andExpect(status().isPreconditionRequired());

        postAs("emp_lead_jordan", "/v1/timecards/" + timecardId + "/adjustments", fix, "If-Match", etag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false))
                .andExpect(jsonPath("$.worked_minutes").value(420))
                .andExpect(jsonPath("$.adjustments[0].reason_code").value("forgot_clock_out"))
                .andExpect(jsonPath("$.punches[0].punch_id").value("pch_marcus_open"));

        // The open shift is closed, so Marcus can clock in again later.
        getAs("emp_lead_jordan", "/v1/locations/loc_deepellum/on_the_clock")
                .andExpect(jsonPath("$[?(@.employee_id == 'emp_marcus')]").isEmpty());

        // A shift lead can adjust but not approve; the GM can approve.
        postAs("emp_lead_jordan", "/v1/timecards/" + timecardId + "/approval", null).andExpect(status().isForbidden());
        postAs("emp_gm_tanya", "/v1/timecards/" + timecardId + "/approval", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approved_by").value("emp_gm_tanya"));
    }

    // ------------------------------------------------------------------ pay periods and payroll

    @Test
    void lockSummarizeAndExportExactlyOnce() throws Exception {
        String pp = "pp_dallas_last_week";

        // Locking fails while timecards are unapproved (seed data leaves one of Marcus's days unapproved).
        JsonNode refused = body(postAs("emp_payroll_priya", "/v1/pay_periods/" + pp + "/lock", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("unapproved_timecards")));
        for (JsonNode id : refused.get("timecard_ids")) {
            approveClosingIfNeeded(id.asText());
        }
        postAs("emp_payroll_priya", "/v1/pay_periods/" + pp + "/lock", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));

        // Summary: Maria worked 5 x 9h = 45h -> 40h regular + 5h overtime at $18.00/h.
        JsonNode summary = body(getAs("emp_payroll_priya", "/v1/pay_periods/" + pp + "/summary").andExpect(status().isOk()));
        JsonNode maria = find(summary.get("employees"), "emp_maria");
        assertThat(maria.get("regular_minutes").asLong()).isEqualTo(2400);
        assertThat(maria.get("overtime_minutes").asLong()).isEqualTo(300);
        assertThat(maria.get("gross_wages_cents").asLong()).isEqualTo(72_000 + 13_500);

        // A shift lead can see hours but not pay.
        getAs("emp_lead_jordan", "/v1/pay_periods/" + pp + "/summary").andExpect(status().isForbidden());

        // Export once.
        String exportId = body(postAs("emp_payroll_priya", "/v1/pay_periods/" + pp + "/exports",
                map("provider", "adp"), "Idempotency-Key", "export-1")
                .andExpect(status().isCreated())).get("id").asText();
        // Same Idempotency-Key: same export back.
        postAs("emp_payroll_priya", "/v1/pay_periods/" + pp + "/exports", map("provider", "adp"), "Idempotency-Key", "export-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(exportId));
        // A second export without saying what it supersedes is refused.
        postAs("emp_payroll_priya", "/v1/pay_periods/" + pp + "/exports", map("provider", "adp"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("export_exists"));
        // An explicit re-export supersedes the first one.
        postAs("emp_payroll_priya", "/v1/pay_periods/" + pp + "/exports", map("provider", "adp", "supersedes", exportId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supersedes_id").value(exportId));

        // Locked periods are frozen: adjusting a timecard inside one is refused with 423.
        String mariaMonday = "tc_emp_maria_" + lastWeekStart().toString().replace("-", "");
        String etag = getAs("emp_lead_jordan", "/v1/timecards/" + mariaMonday).andReturn().getResponse().getHeader("ETag");
        postAs("emp_lead_jordan", "/v1/timecards/" + mariaMonday + "/adjustments",
                map("action", "VOID_PUNCH", "punch_id", "pch_seed_1", "reason_code", "system_error"), "If-Match", etag)
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("pay_period_locked"));

        // ...and the correction goes into the current open period instead.
        postAs("emp_lead_jordan", "/v1/pay_periods/pp_dallas_this_week/prior_period_adjustments",
                map("employee_id", "emp_maria", "original_business_date", lastWeekStart().toString(),
                        "minutes_delta", 15, "reason_code", "missed_punch"))
                .andExpect(status().isCreated());
    }

    @Test
    void californiaPaysDailyOvertime() throws Exception {
        // Ana worked 2 x 11h in Los Angeles: 8h regular + 3h overtime each day.
        JsonNode summary = body(getAs("emp_payroll_priya", "/v1/pay_periods/pp_la_last_week/summary").andExpect(status().isOk()));
        assertThat(summary.get("rule_set").asText()).isEqualTo("US_CALIFORNIA");
        JsonNode ana = find(summary.get("employees"), "emp_ana");
        assertThat(ana.get("regular_minutes").asLong()).isEqualTo(960);
        assertThat(ana.get("overtime_minutes").asLong()).isEqualTo(360);
    }

    @Test
    void payRatesAreRestrictedAndEffectiveDated() throws Exception {
        getAs("emp_lead_jordan", "/v1/employees/emp_maria/pay_rates").andExpect(status().isForbidden());
        getAs("emp_maria", "/v1/employees/me/pay_rates").andExpect(status().isOk());

        postAs("emp_payroll_priya", "/v1/employees/emp_maria/pay_rates",
                map("job_code", "line_cook", "hourly_cents", 1950, "effective_from", LocalDate.now().plusDays(1).toString()))
                .andExpect(status().isCreated());
        JsonNode rates = body(getAs("emp_payroll_priya", "/v1/employees/emp_maria/pay_rates"));
        assertThat(rates.size()).isEqualTo(2);
        assertThat(rates.get(0).get("effective_to").asText()).isEqualTo(LocalDate.now().toString());
    }

    // ------------------------------------------------------------------ scheduling, swaps, time off

    @Test
    void scheduleShiftsSwapAndTimeOff() throws Exception {
        LocalDate nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        String sch = body(postAs("emp_gm_tanya", "/v1/locations/loc_deepellum/schedules",
                map("week_start", nextMonday.toString())).andExpect(status().isCreated())).get("id").asText();

        String start = nextMonday.atTime(11, 0).atZone(ZoneId.of("America/Chicago")).toOffsetDateTime().toString();
        String end = nextMonday.atTime(19, 0).atZone(ZoneId.of("America/Chicago")).toOffsetDateTime().toString();
        String shiftId = body(postAs("emp_gm_tanya", "/v1/schedules/" + sch + "/shifts",
                map("employee_id", "emp_maria", "job_code", "line_cook", "start_at", start, "end_at", end))
                .andExpect(status().isCreated())).get("id").asText();
        postAs("emp_gm_tanya", "/v1/schedules/" + sch + "/shifts",
                map("employee_id", "emp_maria", "job_code", "line_cook", "start_at", start, "end_at", end))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("shift_overlap"));

        // Employees don't see drafts.
        getAs("emp_maria", "/v1/schedules/" + sch).andExpect(status().isForbidden());
        postAs("emp_gm_tanya", "/v1/schedules/" + sch + "/publication", null).andExpect(status().isOk());
        getAs("emp_maria", "/v1/employees/me/shifts?from=" + start + "&to=" + end)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(shiftId));

        // Maria offers the shift, Marcus picks it up, the GM approves.
        String swap = body(postAs("emp_maria", "/v1/shifts/" + shiftId + "/swap_requests", map("note", "family event"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OFFERED"))).get("id").asText();
        postAs("emp_marcus", "/v1/swap_requests/" + swap + "/pickup", null)
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        postAs("emp_gm_tanya", "/v1/swap_requests/" + swap + "/approval", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        getAs("emp_gm_tanya", "/v1/schedules/" + sch)
                .andExpect(jsonPath("$.shifts[0].employee_id").value("emp_marcus"))
                .andExpect(jsonPath("$.shifts[0].changed_after_publish").value(true));

        // Time off: Marcus asks for that Monday; approval lists the shift that now needs covering.
        String tor = body(postAs("emp_marcus", "/v1/employees/me/time_off_requests",
                map("start_date", nextMonday.toString(), "end_date", nextMonday.toString(), "reason", "exam"))
                .andExpect(status().isCreated())).get("id").asText();
        postAs("emp_gm_tanya", "/v1/time_off_requests/" + tor + "/approval", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.conflicting_shift_ids[0]").value(shiftId));
    }

    // ------------------------------------------------------------------ helpers

    /** Approves a timecard; if its shift is still open, a shift lead first adds the missing clock-out. */
    private void approveClosingIfNeeded(String timecardId) throws Exception {
        var res = getAs("emp_lead_jordan", "/v1/timecards/" + timecardId);
        JsonNode tc = body(res);
        if (tc.get("open").asBoolean()) {
            Instant in = Instant.parse(tc.get("punches").get(0).get("at").asText());
            Instant out = in.plus(6, ChronoUnit.HOURS);
            if (out.isAfter(Instant.now())) out = Instant.now().minus(1, ChronoUnit.MINUTES);
            postAs("emp_lead_jordan", "/v1/timecards/" + timecardId + "/adjustments",
                    map("action", "ADD_PUNCH", "punch_type", "CLOCK_OUT", "occurred_at", out.toString(),
                            "reason_code", "forgot_clock_out"),
                    "If-Match", res.andReturn().getResponse().getHeader("ETag"))
                    .andExpect(status().isOk());
        }
        postAs("emp_gm_tanya", "/v1/timecards/" + timecardId + "/approval", null).andExpect(status().isOk());
    }

    private static JsonNode find(JsonNode employees, String id) {
        for (JsonNode e : employees) if (e.get("employee_id").asText().equals(id)) return e;
        throw new AssertionError("No summary line for " + id);
    }

    private static LocalDate lastWeekStart() {
        LocalDate today = com.restaurant.labor.location.BusinessDates.of(Instant.now(), ZoneId.of("America/Chicago"),
                java.time.LocalTime.of(4, 0));
        return com.restaurant.labor.location.BusinessDates.weekStart(today).minusDays(7);
    }
}

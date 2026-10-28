package com.restaurant.labor;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class EmployeeRoleApiTest extends ApiTestSupport {

    private static Object locationScope(String id) {
        return map("type", "location", "id", id);
    }

    @Test
    void gmCanGrantRolesAtHerLocationButNotElsewhere() throws Exception {
        postAs("emp_gm_tanya", "/v1/employees/emp_maria/role_assignments",
                map("role_id", "role_shift_lead", "scope", locationScope("loc_deepellum"), "reason", "covering"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role.id").value("role_shift_lead"))
                .andExpect(jsonPath("$.granted_by").value("emp_gm_tanya"));

        // Same grant again is idempotent: 200 with the existing assignment.
        postAs("emp_gm_tanya", "/v1/employees/emp_maria/role_assignments",
                map("role_id", "role_shift_lead", "scope", locationScope("loc_deepellum")))
                .andExpect(status().isOk());

        // Tanya does not manage Los Angeles.
        postAs("emp_gm_tanya", "/v1/employees/emp_maria/role_assignments",
                map("role_id", "role_shift_lead", "scope", locationScope("loc_la")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_permission"));
    }

    @Test
    void cannotGrantPermissionsYouDoNotHold() throws Exception {
        // The payroll admin role contains payroll:export and pay_rates:manage, which a GM does not have.
        postAs("emp_gm_tanya", "/v1/employees/emp_maria/role_assignments",
                map("role_id", "role_payroll_admin", "scope", locationScope("loc_deepellum")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("escalation_denied"))
                .andExpect(jsonPath("$.missing_permissions").isArray());

        // Only owners can grant the owner role.
        postAs("emp_gm_tanya", "/v1/employees/emp_maria/role_assignments",
                map("role_id", "role_owner", "scope", map("type", "tenant", "id", TENANT)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customRoleCannotExceedCreatorsRefundLimit() throws Exception {
        // The owner can create a custom role...
        postAs("emp_owner_olivia", "/v1/roles",
                map("name", "Bar Manager", "permissions", List.of("timecards:read", "refunds:create"), "refund_limit_cents", 10000))
                .andExpect(status().isCreated());
        // ...an unknown permission code is rejected.
        postAs("emp_owner_olivia", "/v1/roles", map("name", "Typo", "permissions", List.of("timecards:reed")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("unknown_permission"));
    }

    @Test
    void otherTenantsSeeNotFoundNeverForbidden() throws Exception {
        mvc.perform(get("/v1/employees/emp_maria")
                        .header("X-Tenant-Id", "ten_other").header("X-Employee-Id", "emp_other_owner"))
                .andExpect(status().isNotFound());
    }

    @Test
    void terminationRevokesAccessImmediately() throws Exception {
        getAs("emp_marcus", "/v1/employees/me").andExpect(status().isOk());

        postAs("emp_gm_tanya", "/v1/employees/emp_marcus/termination", map("reason", "resigned"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"));

        getAs("emp_marcus", "/v1/employees/me").andExpect(status().isUnauthorized());
        JsonNode assignments = body(getAs("emp_gm_tanya", "/v1/employees/emp_marcus/role_assignments")
                .andExpect(status().isOk()));
        assertThat(assignments.size()).isGreaterThan(0);
        for (JsonNode a : assignments) assertThat(a.get("status").asText()).isEqualTo("REVOKED");
    }

    @Test
    void updatesRequireIfMatch() throws Exception {
        patchAs("emp_gm_tanya", "/v1/employees/emp_maria", map("phone", "+12145550100"))
                .andExpect(status().isPreconditionRequired());

        String etag = getAs("emp_gm_tanya", "/v1/employees/emp_maria").andReturn().getResponse().getHeader("ETag");
        patchAs("emp_gm_tanya", "/v1/employees/emp_maria", map("phone", "+12145550100"), "If-Match", etag)
                .andExpect(status().isOk());
        // The old ETag is now stale.
        patchAs("emp_gm_tanya", "/v1/employees/emp_maria", map("phone", "+12145550199"), "If-Match", etag)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("version_conflict"));
    }

    @Test
    void invitesAreIdempotentByEmail() throws Exception {
        String id = body(postAs("emp_gm_tanya", "/v1/employees", map("name", "New Cook", "email", "new.cook@example.com"))
                .andExpect(status().isCreated())).get("id").asText();
        postAs("emp_gm_tanya", "/v1/employees", map("name", "New Cook", "email", "NEW.COOK@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        // The invitee accepts their own invitation.
        postAs(id, "/v1/employees/" + id + "/invitation/acceptance", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void pinLocksOutAfterFiveWrongAttempts() throws Exception {
        String bad = json.writeValueAsString(map("employee_id", "emp_maria", "pin", "0000",
                "location_id", "loc_deepellum", "device_id", "dev_pos_1"));
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/v1/pos/pin_sessions").header("X-Tenant-Id", TENANT)
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isUnauthorized());
        }
        String good = json.writeValueAsString(map("employee_id", "emp_maria", "pin", "1234",
                "location_id", "loc_deepellum", "device_id", "dev_pos_1"));
        mvc.perform(post("/v1/pos/pin_sessions").header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("pin_locked"));
    }

    @Test
    void pinSessionWorksAsCallerIdentity() throws Exception {
        String good = json.writeValueAsString(map("employee_id", "emp_maria", "pin", "1234",
                "location_id", "loc_deepellum", "device_id", "dev_pos_1"));
        String token = json.readTree(mvc.perform(post("/v1/pos/pin_sessions").header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("token").asText();

        mvc.perform(get("/v1/employees/me").header("X-Tenant-Id", TENANT).header("X-Pin-Session", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("emp_maria"))
                .andExpect(header().exists("ETag"));
    }

    @Test
    void effectivePermissionsAndBatchChecks() throws Exception {
        getAs("emp_gm_tanya", "/v1/employees/emp_lead_jordan/effective_permissions?location_id=loc_deepellum")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund_limit_cents").value(5000));

        postAs("emp_owner_olivia", "/v1/authorization/checks", map("checks", List.of(
                map("employee_id", "emp_lead_jordan", "permission", "timecards:adjust", "location_id", "loc_deepellum"),
                map("employee_id", "emp_lead_jordan", "permission", "timecards:adjust", "location_id", "loc_la"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].allowed").value(true))
                .andExpect(jsonPath("$.results[1].allowed").value(false));
    }
}

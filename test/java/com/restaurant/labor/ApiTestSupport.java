package com.restaurant.labor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Small helpers so tests read like "as Tanya, POST this". */
public abstract class ApiTestSupport {

    protected static final String TENANT = "ten_pecan";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    protected ResultActions getAs(String employeeId, String url) throws Exception {
        return mvc.perform(caller(get(url), employeeId));
    }

    protected ResultActions postAs(String employeeId, String url, Object body, String... headers) throws Exception {
        MockHttpServletRequestBuilder b = caller(post(url), employeeId);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(withHeaders(b, headers));
    }

    protected ResultActions patchAs(String employeeId, String url, Object body, String... headers) throws Exception {
        MockHttpServletRequestBuilder b = caller(patch(url), employeeId)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(withHeaders(b, headers));
    }

    protected ResultActions deleteAs(String employeeId, String url) throws Exception {
        return mvc.perform(caller(delete(url), employeeId));
    }

    protected JsonNode body(ResultActions actions) throws Exception {
        MvcResult r = actions.andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    protected static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static MockHttpServletRequestBuilder caller(MockHttpServletRequestBuilder b, String employeeId) {
        b.header("X-Tenant-Id", TENANT);
        if (employeeId != null) b.header("X-Employee-Id", employeeId);
        return b;
    }

    private static MockHttpServletRequestBuilder withHeaders(MockHttpServletRequestBuilder b, String... headers) {
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return b;
    }
}

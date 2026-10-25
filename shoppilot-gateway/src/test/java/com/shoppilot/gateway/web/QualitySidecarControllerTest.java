package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.quality.QualitySidecarService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** H4：质量报表是运维面；无凭证拒绝，带凭证只读可查。 */
class QualitySidecarControllerTest {

    private static final String OPS_TOKEN = "ops-secret";
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObjectMapper mapper = new ObjectMapper();
        GatewayProperties properties = new GatewayProperties(null, null, null, null, null, null, null, null,
                null, null, null, new GatewayProperties.Ops(true, OPS_TOKEN), null);
        mvc = MockMvcBuilders.standaloneSetup(new QualitySidecarController(
                new QualitySidecarService(registry, null), properties, mapper, new ApiErrorWriter(mapper))).build();
    }

    @Test
    @DisplayName("缺 ops 凭证 → 403")
    void rejectsMissingOpsToken() throws Exception {
        mvc.perform(get("/api/v1/support/ops/quality/report"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    @DisplayName("ops 凭证正确 → 返回只读质量报表")
    void returnsReadOnlyReportForOps() throws Exception {
        mvc.perform(get("/api/v1/support/ops/quality/report")
                        .header("X-Ops-Token", OPS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("process-local-cumulative"))
                .andExpect(jsonPath("$.handoff.blockedRatio").value(0.0d))
                .andExpect(jsonPath("$.friction.repeatedSlotAsks").value(0.0d));
    }
}

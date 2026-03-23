package com.effectivedisco.loadtest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.load-test.enabled=true")
class LoadTestMetricsControllerTest {

    @Autowired WebApplicationContext context;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void metricsEndpoint_withoutAuth_returnsSnapshot() throws Exception {
        mockMvc.perform(get("/internal/load-test/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateKeyConflicts").value(0))
                .andExpect(jsonPath("$.dbPoolTimeouts").value(0))
                .andExpect(jsonPath("$.bottleneckProfiles").isArray())
                .andExpect(jsonPath("$.currentActiveConnections").exists())
                .andExpect(jsonPath("$.maxThreadsAwaitingConnection").exists());
    }

    @Test
    void resetEndpoint_withoutAuth_resetsSnapshot() throws Exception {
        mockMvc.perform(post("/internal/load-test/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateKeyConflicts").value(0))
                .andExpect(jsonPath("$.dbPoolTimeouts").value(0))
                .andExpect(jsonPath("$.bottleneckProfiles").isArray());
    }
}

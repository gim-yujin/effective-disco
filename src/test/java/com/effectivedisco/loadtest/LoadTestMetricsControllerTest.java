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

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.load-test.enabled=true")
class LoadTestMetricsControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean LoadTestDataCleanupService loadTestDataCleanupService;

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
                .andExpect(jsonPath("$.jwtAuthCacheHits").value(0))
                .andExpect(jsonPath("$.jwtAuthCacheMisses").value(0))
                .andExpect(jsonPath("$.bottleneckProfiles").isArray())
                .andExpect(jsonPath("$.postgresSnapshot.available").exists())
                .andExpect(jsonPath("$.postgresSnapshot.slowActiveQueries").isArray())
                .andExpect(jsonPath("$.currentActiveConnections").exists())
                .andExpect(jsonPath("$.maxThreadsAwaitingConnection").exists());
    }

    @Test
    void resetEndpoint_withoutAuth_resetsSnapshot() throws Exception {
        mockMvc.perform(post("/internal/load-test/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateKeyConflicts").value(0))
                .andExpect(jsonPath("$.dbPoolTimeouts").value(0))
                .andExpect(jsonPath("$.jwtAuthCacheHits").value(0))
                .andExpect(jsonPath("$.jwtAuthCacheMisses").value(0))
                .andExpect(jsonPath("$.bottleneckProfiles").isArray())
                .andExpect(jsonPath("$.postgresSnapshot.available").exists());
    }

    @Test
    void cleanupEndpoint_withoutAuth_returnsDeletedScopeSummary() throws Exception {
        given(loadTestDataCleanupService.cleanupByPrefix("lt-demo"))
                .willReturn(new LoadTestDataCleanupService.CleanupSummary(
                        "lt-demo",
                        3,
                        9,
                        4,
                        2,
                        1,
                        1
                ));

        mockMvc.perform(post("/internal/load-test/cleanup")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prefix": "lt-demo"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefix").value("lt-demo"))
                .andExpect(jsonPath("$.matchedUsers").value(3))
                .andExpect(jsonPath("$.matchedPosts").value(9))
                .andExpect(jsonPath("$.matchedComments").value(4))
                .andExpect(jsonPath("$.matchedNotifications").value(2))
                .andExpect(jsonPath("$.matchedMessages").value(1))
                .andExpect(jsonPath("$.matchedPasswordResetTokens").value(1));
    }
}

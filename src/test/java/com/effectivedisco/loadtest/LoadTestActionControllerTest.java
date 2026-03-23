package com.effectivedisco.loadtest;

import com.effectivedisco.service.BlockService;
import com.effectivedisco.service.BookmarkService;
import com.effectivedisco.service.FollowService;
import com.effectivedisco.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.load-test.enabled=true")
class LoadTestActionControllerTest {

    @Autowired WebApplicationContext context;

    @MockitoBean FollowService followService;
    @MockitoBean BookmarkService bookmarkService;
    @MockitoBean BlockService blockService;
    @MockitoBean NotificationService notificationService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void followEndpoint_withoutAuth_invokesServiceAndReturnsState() throws Exception {
        given(followService.isFollowing("actor", "target")).willReturn(true);

        mockMvc.perform(post("/internal/load-test/actions/follow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorUsername": "actor",
                                  "targetUsername": "target"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        verify(followService).follow("actor", "target");
        verify(followService).isFollowing("actor", "target");
    }

    @Test
    void notificationReadEndpoint_withoutAuth_returnsUnreadCount() throws Exception {
        given(notificationService.markAllAsReadForLoadTest("reader"))
                .willReturn(new NotificationService.NotificationReadSummary(2, 0L));

        mockMvc.perform(post("/internal/load-test/actions/notifications/read-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "reader"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listedNotificationCount").value(2))
                .andExpect(jsonPath("$.unreadCount").value(0));

        verify(notificationService).markAllAsReadForLoadTest("reader");
    }
}

package com.effectivedisco.controller.web;

import com.effectivedisco.domain.Message;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.MessageRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MessageWebController 통합 테스트.
 *
 * 검증 대상:
 *   GET  /messages              받은 편지함 (인증 필요)
 *   GET  /messages/sent         보낸 편지함 (인증 필요)
 *   GET  /messages/compose      쪽지 작성 폼
 *   POST /messages              쪽지 전송 (성공·수신자 없음)
 *   GET  /messages/{id}         쪽지 상세 (본인만)
 *   POST /messages/{id}/delete  쪽지 삭제
 *
 * 접근 제어:
 *   SecurityConfig: /messages/** → authenticated()
 *   미인증 접근은 /login 으로 리다이렉트되어야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MessageWebControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository        userRepository;
    @Autowired MessageRepository     messageRepository;
    @Autowired PasswordEncoder       passwordEncoder;

    MockMvc mockMvc;
    User    sender;    // 쪽지 발신자
    User    recipient; // 쪽지 수신자

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        sender    = userRepository.save(User.builder()
                .username("sender").email("sender@test.com")
                .password(passwordEncoder.encode("pass")).build());
        recipient = userRepository.save(User.builder()
                .username("recipient").email("recipient@test.com")
                .password(passwordEncoder.encode("pass")).build());
    }

    // ── 접근 제어 ────────────────────────────────────────────

    /**
     * 미인증 사용자가 받은 편지함에 접근하면 로그인 페이지로 리다이렉트된다.
     */
    @Test
    void inbox_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/messages"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /**
     * 인증된 사용자는 받은 편지함에서 200을 받아야 한다.
     * 모델에 messages 와 box="inbox" 속성이 포함되어야 한다.
     */
    @Test
    void inbox_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/messages").with(user(sender.getUsername())))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("messages"))
                .andExpect(model().attribute("box", "inbox"))
                .andExpect(view().name("messages/inbox"));
    }

    /**
     * 인증된 사용자는 보낸 편지함에서 200을 받아야 한다.
     * box 속성이 "sent" 여야 한다 (받은/보낸 편지함은 동일 템플릿을 공유).
     */
    @Test
    void sent_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/messages/sent").with(user(sender.getUsername())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("box", "sent"))
                .andExpect(view().name("messages/inbox"));
    }

    // ── 쪽지 작성 폼 ─────────────────────────────────────────

    /**
     * GET /messages/compose 는 인증된 사용자에게 작성 폼을 반환해야 한다.
     * to 파라미터가 있으면 수신자 필드에 미리 채워진다.
     */
    @Test
    void composeForm_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/messages/compose")
                        .with(user(sender.getUsername()))
                        .param("to", recipient.getUsername()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("messageRequest"))
                .andExpect(view().name("messages/compose"));
    }

    // ── 쪽지 전송 ────────────────────────────────────────────

    /**
     * 유효한 수신자에게 쪽지를 전송하면 보낸 편지함으로 리다이렉트되어야 한다.
     * 성공 플래시 메시지(successMsg)가 설정되어야 한다.
     */
    @Test
    void send_validRecipient_redirectsToSent() throws Exception {
        mockMvc.perform(post("/messages")
                        .with(csrf())
                        .with(user(sender.getUsername()))
                        .param("to",      recipient.getUsername())
                        .param("title",   "안녕하세요")
                        .param("content", "테스트 쪽지 내용입니다."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/messages/sent"))
                .andExpect(flash().attributeExists("successMsg"));
    }

    /**
     * 자기 자신에게 쪽지를 보내면 서비스에서 IllegalArgumentException 이 발생하고
     * 컨트롤러가 이를 catch 해 다시 작성 폼을 표시해야 한다.
     *
     * MessageService: "자기 자신에게는 쪽지를 보낼 수 없습니다." → IllegalArgumentException
     * MessageWebController: catch (IllegalArgumentException e) → return "messages/compose"
     *
     * 주의: 존재하지 않는 수신자의 경우 MessageService.findUser()가 UsernameNotFoundException
     * (AuthenticationException 하위)을 던지므로 Spring Security가 /login 으로 리다이렉트한다.
     * 이 케이스는 서비스 단에서 IllegalArgumentException 으로 감싸지 않으므로 여기서는 테스트하지 않는다.
     */
    @Test
    void send_selfMessage_returnsComposeForm() throws Exception {
        mockMvc.perform(post("/messages")
                        .with(csrf())
                        .with(user(sender.getUsername()))
                        .param("to",      sender.getUsername())   // 자기 자신에게 전송
                        .param("title",   "제목")
                        .param("content", "내용"))
                .andExpect(status().isOk())
                .andExpect(view().name("messages/compose"));
    }

    /**
     * to 필드가 비어있으면 @Valid 검증 실패로 작성 폼이 다시 표시된다.
     */
    @Test
    void send_blankRecipient_returnsComposeForm() throws Exception {
        mockMvc.perform(post("/messages")
                        .with(csrf())
                        .with(user(sender.getUsername()))
                        .param("to",      "")        // @NotBlank 위반
                        .param("title",   "제목")
                        .param("content", "내용"))
                .andExpect(status().isOk())
                .andExpect(view().name("messages/compose"));
    }

    // ── 쪽지 상세 ────────────────────────────────────────────

    /**
     * 수신자가 자신의 쪽지 상세를 조회하면 200과 message 모델을 반환해야 한다.
     */
    @Test
    void detail_byRecipient_returns200() throws Exception {
        Message msg = messageRepository.save(Message.builder()
                .sender(sender)
                .recipient(recipient)
                .title("테스트 쪽지")
                .content("내용")
                .build());

        mockMvc.perform(get("/messages/{id}", msg.getId())
                        .with(user(recipient.getUsername())))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("message"))
                .andExpect(view().name("messages/detail"));
    }

    // ── 쪽지 삭제 ────────────────────────────────────────────

    /**
     * 수신자가 받은 편지함에서 쪽지를 삭제하면 받은 편지함으로 리다이렉트되어야 한다.
     * 실제 DB 삭제가 아닌 deletedByRecipient 플래그를 사용하는 소프트 삭제.
     */
    @Test
    void delete_fromInbox_redirectsToInbox() throws Exception {
        Message msg = messageRepository.save(Message.builder()
                .sender(sender)
                .recipient(recipient)
                .title("삭제할 쪽지")
                .content("내용")
                .build());

        mockMvc.perform(post("/messages/{id}/delete", msg.getId())
                        .with(csrf())
                        .with(user(recipient.getUsername()))
                        .param("box", "inbox"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/messages"));
    }

    /**
     * 발신자가 보낸 편지함에서 쪽지를 삭제하면 보낸 편지함으로 리다이렉트되어야 한다.
     */
    @Test
    void delete_fromSent_redirectsToSent() throws Exception {
        Message msg = messageRepository.save(Message.builder()
                .sender(sender)
                .recipient(recipient)
                .title("삭제할 쪽지")
                .content("내용")
                .build());

        mockMvc.perform(post("/messages/{id}/delete", msg.getId())
                        .with(csrf())
                        .with(user(sender.getUsername()))
                        .param("box", "sent"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/messages/sent"));
    }
}

package com.effectivedisco.service;

import com.effectivedisco.domain.Notification;
import com.effectivedisco.domain.NotificationSetting;
import com.effectivedisco.domain.NotificationType;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.NotificationSettingRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class MentionNotificationIntegrationTest {

    @Autowired NotificationService notificationService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationSettingRepository notificationSettingRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PlatformTransactionManager transactionManager;

    TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        notificationRepository.deleteAll();
        notificationSettingRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void notifyMentions_createsMentionNotificationForExistingUser() {
        saveUser("alice");
        User bob = saveUser("bob");

        transactionTemplate.executeWithoutResult(status ->
                notificationService.notifyMentions("@bob 안녕", "alice", "/posts/1"));

        List<Notification> notifications = notificationRepository.findByRecipientOrderByCreatedAtDesc(
                userRepository.findByUsername("bob").orElseThrow());
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getType()).isEqualTo(NotificationType.MENTION);
        assertThat(notifications.get(0).getMessage()).contains("alice");
        assertThat(notifications.get(0).getLink()).isEqualTo("/posts/1");
        assertThat(userRepository.findUnreadNotificationCountByUsername("bob")).hasValue(1L);
        // bob 이 alice 가 아닌 자신을 언급하지 않았으므로 alice 에게는 알림이 없어야 한다
        assertThat(userRepository.findUnreadNotificationCountByUsername("alice")).hasValue(0L);
        // bob 객체는 실제 저장된 상태이지만 위 find* 로 재조회했으므로 로컬 변수는 단순 참조
        assertThat(bob.getUsername()).isEqualTo("bob");
    }

    @Test
    void notifyMentions_selfMention_isIgnored() {
        saveUser("alice");

        transactionTemplate.executeWithoutResult(status ->
                notificationService.notifyMentions("@alice 테스트", "alice", "/posts/1"));

        assertThat(notificationRepository.findAll()).isEmpty();
        assertThat(userRepository.findUnreadNotificationCountByUsername("alice")).hasValue(0L);
    }

    @Test
    void notifyMentions_nonExistentUsername_isSilentlyIgnored() {
        saveUser("alice");

        transactionTemplate.executeWithoutResult(status ->
                notificationService.notifyMentions("@ghost 안녕", "alice", "/posts/1"));

        assertThat(notificationRepository.findAll()).isEmpty();
    }

    @Test
    void notifyMentions_multipleRecipients_emitsOnePerExistingUser() {
        saveUser("alice");
        saveUser("bob");
        saveUser("carol");

        transactionTemplate.executeWithoutResult(status ->
                notificationService.notifyMentions("@bob and @carol and @ghost", "alice", "/posts/42"));

        assertThat(userRepository.findUnreadNotificationCountByUsername("bob")).hasValue(1L);
        assertThat(userRepository.findUnreadNotificationCountByUsername("carol")).hasValue(1L);
    }

    @Test
    void notifyNewMentions_onEdit_onlyNotifiesAddedMentions() {
        saveUser("alice");
        saveUser("bob");
        saveUser("carol");

        transactionTemplate.executeWithoutResult(status ->
                notificationService.notifyMentions("@bob", "alice", "/posts/1"));
        assertThat(userRepository.findUnreadNotificationCountByUsername("bob")).hasValue(1L);

        transactionTemplate.executeWithoutResult(status ->
                notificationService.notifyNewMentions("@bob", "@bob @carol", "alice", "/posts/1"));

        // bob 은 이미 원문에 있었으므로 재알림 없음
        assertThat(userRepository.findUnreadNotificationCountByUsername("bob")).hasValue(1L);
        // carol 은 신규 추가됐으므로 알림 1건
        assertThat(userRepository.findUnreadNotificationCountByUsername("carol")).hasValue(1L);
    }

    @Test
    void notifyMentions_recipientDisabledMention_skipsNotification() {
        saveUser("alice");
        User bob = saveUser("bob");
        notificationSettingRepository.save(new NotificationSetting(bob, NotificationType.MENTION, false));

        transactionTemplate.executeWithoutResult(status ->
                notificationService.notifyMentions("@bob 안녕", "alice", "/posts/1"));

        assertThat(notificationRepository.findAll()).isEmpty();
        assertThat(userRepository.findUnreadNotificationCountByUsername("bob")).hasValue(0L);
    }

    private User saveUser(String username) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@test.com")
                .password(passwordEncoder.encode("pass"))
                .build());
    }
}

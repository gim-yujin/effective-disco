package com.effectivedisco.service;

import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.PasswordChangeRequest;
import com.effectivedisco.dto.request.ProfileEditRequest;
import com.effectivedisco.repository.BlockRepository;
import com.effectivedisco.repository.CommentRepository;
import com.effectivedisco.repository.FollowRepository;
import com.effectivedisco.repository.MessageRepository;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.ReportRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository         userRepository;
    @Mock PostRepository         postRepository;
    @Mock CommentRepository      commentRepository;
    @Mock PostLikeRepository     postLikeRepository;
    @Mock NotificationRepository notificationRepository;
    @Mock MessageRepository      messageRepository;
    @Mock ReportRepository       reportRepository;
    @Mock FollowRepository       followRepository;  // 팔로우 기능 추가로 필요
    @Mock BlockRepository        blockRepository;   // 사용자 차단 기능 추가로 필요
    @Mock PasswordEncoder        passwordEncoder;

    @InjectMocks UserService userService;

    // ── updateProfile ─────────────────────────────────────────

    @Test
    void updateProfile_newEmail_updatesEmail() {
        User user = makeUser("alice", "alice@old.com", "encoded");
        ProfileEditRequest req = profileReq("alice@new.com", "Hello!");

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(userRepository.existsByEmail("alice@new.com")).willReturn(false);

        userService.updateProfile("alice", req);

        // email and bio should now be updated on the entity
        org.assertj.core.api.Assertions.assertThat(user.getEmail()).isEqualTo("alice@new.com");
        org.assertj.core.api.Assertions.assertThat(user.getBio()).isEqualTo("Hello!");
    }

    @Test
    void updateProfile_duplicateEmail_throwsIllegalArgumentException() {
        User user = makeUser("alice", "alice@old.com", "encoded");
        ProfileEditRequest req = profileReq("taken@other.com", null);

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(userRepository.existsByEmail("taken@other.com")).willReturn(true);

        assertThatThrownBy(() -> userService.updateProfile("alice", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 사용 중인 이메일");
    }

    @Test
    void updateProfile_sameEmail_doesNotCheckDuplicate() {
        User user = makeUser("alice", "alice@same.com", "encoded");
        ProfileEditRequest req = profileReq("alice@same.com", "bio");

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));

        // should not call existsByEmail when email hasn't changed
        userService.updateProfile("alice", req);

        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never())
                .existsByEmail(anyString());
    }

    // ── changePassword ────────────────────────────────────────

    @Test
    void changePassword_success_encodesAndUpdates() {
        User user = makeUser("alice", "alice@test.com", "encoded_old");
        PasswordChangeRequest req = pwReq("oldPw", "newPw123", "newPw123");

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("oldPw", "encoded_old")).willReturn(true);
        given(passwordEncoder.encode("newPw123")).willReturn("encoded_new");

        userService.changePassword("alice", req);

        org.assertj.core.api.Assertions.assertThat(user.getPassword()).isEqualTo("encoded_new");
    }

    @Test
    void changePassword_wrongCurrentPassword_throwsException() {
        User user = makeUser("alice", "alice@test.com", "encoded");
        PasswordChangeRequest req = pwReq("wrongPw", "newPw123", "newPw123");

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrongPw", "encoded")).willReturn(false);

        assertThatThrownBy(() -> userService.changePassword("alice", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("현재 비밀번호가 올바르지 않습니다");
    }

    @Test
    void changePassword_confirmMismatch_throwsException() {
        User user = makeUser("alice", "alice@test.com", "encoded");
        PasswordChangeRequest req = pwReq("oldPw", "newPw123", "differentPw");

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("oldPw", "encoded")).willReturn(true);

        assertThatThrownBy(() -> userService.changePassword("alice", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("새 비밀번호 확인이 일치하지 않습니다");
    }

    // ── withdraw ──────────────────────────────────────────────

    @Test
    void withdraw_success_deletesRelatedDataAndUser() {
        User user = makeUser("alice", "alice@test.com", "encoded");
        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("myPw", "encoded")).willReturn(true);

        userService.withdraw("alice", "myPw");

        verify(notificationRepository).deleteAllByRecipient(user);
        verify(messageRepository).deleteAllByUser(user);
        verify(postLikeRepository).deleteByUser(user);
        verify(postLikeRepository).deleteByPostAuthor(user);
        verify(reportRepository).deleteByReporter(user);
        verify(userRepository).delete(user);
    }

    @Test
    void withdraw_wrongPassword_throwsException() {
        User user = makeUser("alice", "alice@test.com", "encoded");
        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrongPw", "encoded")).willReturn(false);

        assertThatThrownBy(() -> userService.withdraw("alice", "wrongPw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비밀번호가 올바르지 않습니다");
    }

    // ── helpers ───────────────────────────────────────────────

    private User makeUser(String username, String email, String encodedPassword) {
        User user = User.builder().username(username).email(email).password(encodedPassword).build();
        return user;
    }

    private ProfileEditRequest profileReq(String email, String bio) {
        ProfileEditRequest req = new ProfileEditRequest();
        req.setEmail(email);
        req.setBio(bio);
        return req;
    }

    private PasswordChangeRequest pwReq(String current, String newPw, String confirm) {
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setCurrentPassword(current);
        req.setNewPassword(newPw);
        req.setConfirmPassword(confirm);
        return req;
    }
}

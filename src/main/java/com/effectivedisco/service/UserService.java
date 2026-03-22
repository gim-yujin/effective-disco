package com.effectivedisco.service;

import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.PasswordChangeRequest;
import com.effectivedisco.dto.request.ProfileEditRequest;
import com.effectivedisco.dto.response.UserProfileResponse;

import java.time.LocalDateTime;
import com.effectivedisco.repository.CommentRepository;
import com.effectivedisco.repository.MessageRepository;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.ReportRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 관련 비즈니스 로직.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository         userRepository;
    private final PostRepository         postRepository;
    private final CommentRepository      commentRepository;
    private final PostLikeRepository     postLikeRepository;
    private final NotificationRepository notificationRepository;
    private final MessageRepository      messageRepository;
    private final ReportRepository       reportRepository;
    private final PasswordEncoder        passwordEncoder;

    /**
     * 사용자 프로필 정보를 조회한다.
     *
     * 반환 데이터:
     * - 기본 정보: username, 가입일
     * - 활동 통계: 게시물 수, 댓글 수, 받은 좋아요 수
     *
     * @param username 조회할 사용자명
     * @throws UsernameNotFoundException 존재하지 않는 사용자
     */
    public UserProfileResponse getProfile(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "존재하지 않는 사용자입니다: " + username));

        long postCount     = postRepository.countByAuthor(user);
        long commentCount  = commentRepository.countByAuthor(user);
        // 내가 작성한 게시물 전체에 달린 좋아요 합산
        long likesReceived = postLikeRepository.countLikesReceivedByUser(user);

        return new UserProfileResponse(user, postCount, commentCount, likesReceived);
    }

    /* ── 프로필 편집 ──────────────────────────────────────────── */

    /**
     * bio·email 변경.
     * 이메일이 다른 계정에서 이미 사용 중이면 예외 발생.
     */
    @Transactional
    public void updateProfile(String username, ProfileEditRequest req) {
        User user = findUser(username);

        String newEmail = req.getEmail();
        if (newEmail != null && !newEmail.isBlank() && !newEmail.equals(user.getEmail())) {
            if (userRepository.existsByEmail(newEmail)) {
                throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
            }
            user.updateEmail(newEmail);
        }

        if (req.getBio() != null) {
            user.updateBio(req.getBio().isBlank() ? null : req.getBio());
        }
    }

    /**
     * 비밀번호 변경.
     * 현재 비밀번호가 틀리거나 새 비밀번호 확인이 불일치하면 예외 발생.
     */
    @Transactional
    public void changePassword(String username, PasswordChangeRequest req) {
        User user = findUser(username);

        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("현재 비밀번호가 올바르지 않습니다.");
        }
        if (!req.getNewPassword().equals(req.getConfirmPassword())) {
            throw new IllegalArgumentException("새 비밀번호 확인이 일치하지 않습니다.");
        }

        user.updatePassword(passwordEncoder.encode(req.getNewPassword()));
    }

    /**
     * 회원 탈퇴.
     * FK 제약 위반을 방지하기 위해 관련 데이터를 순서대로 먼저 삭제한 뒤 계정을 삭제한다.
     * User.posts / User.comments 는 cascade CascadeType.ALL로 자동 삭제된다.
     */
    @Transactional
    public void withdraw(String username, String password) {
        User user = findUser(username);

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 올바르지 않습니다.");
        }

        // 1. 알림 (recipient FK)
        notificationRepository.deleteAllByRecipient(user);
        // 2. 쪽지 (sender / recipient FK)
        messageRepository.deleteAllByUser(user);
        // 3. 이 사용자가 누른 좋아요 (user FK)
        postLikeRepository.deleteByUser(user);
        // 4. 이 사용자 게시물에 달린 좋아요 (post FK — cascade 전 선행 삭제)
        postLikeRepository.deleteByPostAuthor(user);
        // 5. 제출한 신고 (reporter FK)
        reportRepository.deleteByReporter(user);
        // 6. 계정 삭제 (cascade: posts → comments)
        userRepository.delete(user);
    }

    /* ── 계정 정지 관리 (관리자 전용) ────────────────────────── */

    /**
     * 사용자 계정을 정지한다.
     *
     * @param userId 정지할 사용자 ID
     * @param reason 정지 사유 (관리자 입력, 빈 문자열이면 null 저장)
     * @param days   정지 일수. null 이면 영구 정지
     * @throws IllegalArgumentException 대상 사용자를 찾을 수 없을 때
     */
    @Transactional
    public void suspendUser(Long userId, String reason, Integer days) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));

        // 정지 해제 시각 계산: days = null 이면 영구 정지 (until = null)
        LocalDateTime until = (days != null && days > 0)
                ? LocalDateTime.now().plusDays(days)
                : null;

        String trimmedReason = (reason != null && !reason.isBlank()) ? reason.trim() : null;
        target.suspend(trimmedReason, until);
    }

    /**
     * 사용자 계정 정지를 해제한다.
     *
     * @param userId 정지를 해제할 사용자 ID
     */
    @Transactional
    public void unsuspendUser(Long userId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));
        target.unsuspend();
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "존재하지 않는 사용자입니다: " + username));
    }
}

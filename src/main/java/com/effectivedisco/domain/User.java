package com.effectivedisco.domain;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "author", cascade = CascadeType.ALL)
    private List<Post> posts = new ArrayList<>();

    @OneToMany(mappedBy = "author", cascade = CascadeType.ALL)
    private List<Comment> comments = new ArrayList<>();

    /**
     * 권한 역할. "ROLE_USER" 또는 "ROLE_ADMIN".
     * Spring Security의 hasRole("ADMIN") 검사와 일치시키기 위해 ROLE_ 접두사를 포함한다.
     */
    @Column(nullable = false, columnDefinition = "varchar(255) default 'ROLE_USER'")
    private String role = "ROLE_USER";

    /** 자기 소개 (선택 입력, 최대 300자) */
    @Column(columnDefinition = "TEXT")
    private String bio;

    /**
     * 프로필 이미지 URL.
     * null 이면 이름 첫 글자를 이니셜 아바타로 표시한다.
     * 값이 있으면 해당 이미지를 프로필 카드와 댓글 목록에 표시한다.
     */
    @Column
    private String profileImageUrl;

    /**
     * 계정 정지 여부.
     * true 이면 해당 사용자는 로그인 시 LockedException 이 발생한다.
     * DDL 기본값을 false 로 지정해 기존 행의 NULL 방지.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean suspended = false;

    /**
     * 정지 해제 시각. null 이면 영구 정지.
     * 현재 시각이 이 값을 넘으면 자동으로 접근이 허용된다.
     */
    @Column
    private LocalDateTime suspendedUntil;

    /** 정지 사유 (관리자 입력, 선택) */
    @Column
    private String suspensionReason;

    @Builder
    public User(String username, String email, String password) {
        this.username = username;
        this.email = email;
        this.password = password;
        this.role = "ROLE_USER";
        this.createdAt = LocalDateTime.now();
    }

    /** 관리자 권한 부여/회수 */
    public void promoteToAdmin()  { this.role = "ROLE_ADMIN"; }
    public void demoteToUser()    { this.role = "ROLE_USER";  }
    public boolean isAdmin()      { return "ROLE_ADMIN".equals(this.role); }

    /** 프로필 편집 */
    public void updateBio(String bio)                    { this.bio = bio; }
    public void updateEmail(String email)                { this.email = email; }
    public void updatePassword(String encoded)           { this.password = encoded; }
    /** 프로필 이미지 URL 변경 (null 허용 — null 이면 이니셜 아바타로 되돌림) */
    public void updateProfileImageUrl(String url)        { this.profileImageUrl = url; }

    /* ── 계정 정지 관리 ───────────────────────────────────────── */

    /**
     * 계정을 정지한다.
     *
     * @param reason 정지 사유 (null 허용)
     * @param until  정지 해제 시각. null 이면 영구 정지
     */
    public void suspend(String reason, LocalDateTime until) {
        this.suspended        = true;
        this.suspensionReason = reason;
        this.suspendedUntil   = until;
    }

    /**
     * 계정 정지를 해제한다.
     * suspended·suspendedUntil·suspensionReason 을 모두 초기화한다.
     */
    public void unsuspend() {
        this.suspended        = false;
        this.suspendedUntil   = null;
        this.suspensionReason = null;
    }

    /**
     * 현재 시점에서 실제로 정지 상태인지 반환한다.
     *
     * suspended = true 이더라도 suspendedUntil 이 이미 지난 경우 false 를 반환한다
     * (기간 만료 → 자동 허용).
     */
    public boolean isCurrentlySuspended() {
        if (!this.suspended) return false;
        // 영구 정지: until = null
        if (this.suspendedUntil == null) return true;
        // 기간 정지: 아직 만료 시각 전이면 정지 상태
        return LocalDateTime.now().isBefore(this.suspendedUntil);
    }
}

package com.effectivedisco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * 팔로우 관계 엔티티.
 *
 * follower  → following 방향의 단방향 팔로우를 표현한다.
 * (A가 B를 팔로우한다 = follower=A, following=B)
 *
 * 유니크 제약으로 동일 쌍의 중복 팔로우를 방지한다.
 *
 * 삭제 정책:
 * - 팔로워 또는 팔로잉 사용자 탈퇴 시 DB 레벨 CASCADE로 자동 삭제.
 */
@Entity
@Table(name = "follows",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_follow_follower_following",
               columnNames = {"follower_id", "following_id"}))
@Getter
@NoArgsConstructor
public class Follow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 팔로우를 거는 사람 (구독자) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "follower_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User follower;

    /** 팔로우를 받는 사람 (피구독자) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "following_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User following;

    /** 팔로우 시각 */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Follow(User follower, User following) {
        this.follower  = follower;
        this.following = following;
        this.createdAt = LocalDateTime.now();
    }
}

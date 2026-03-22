package com.effectivedisco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * 사용자 차단 엔티티.
 *
 * blocker(차단한 사람)가 blocked(차단당한 사람)를 차단한 단방향 관계를 나타낸다.
 * 차단 후 blocker의 화면에서 blocked가 작성한 게시물·댓글이 숨겨진다.
 *
 * 삭제 정책:
 * - 어느 한 쪽 사용자가 탈퇴하면 DB 레벨 ON DELETE CASCADE(@OnDelete)로 자동 삭제된다.
 * - UserService.withdraw()에서도 명시적으로 삭제해 JPA 레벨 일관성을 보장한다.
 */
@Entity
@Table(name = "blocks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_block_blocker_blocked",
                columnNames = {"blocker_id", "blocked_id"}))
@Getter
@NoArgsConstructor
public class Block {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 차단을 수행한 사용자. 탈퇴 시 DB CASCADE 삭제. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocker_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User blocker;

    /** 차단당한 사용자. 탈퇴 시 DB CASCADE 삭제. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocked_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User blocked;

    /** 차단한 시각. */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Block(User blocker, User blocked) {
        this.blocker   = blocker;
        this.blocked   = blocked;
        this.createdAt = LocalDateTime.now();
    }
}

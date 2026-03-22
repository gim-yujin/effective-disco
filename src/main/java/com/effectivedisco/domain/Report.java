package com.effectivedisco.domain;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 게시물·댓글 신고 엔티티.
 *
 * 중복 신고 방지: reporter + targetType + targetId 조합은 유일해야 한다.
 * 상태 전이: PENDING → RESOLVED | DISMISSED (관리자가 처리)
 */
@Entity
@Table(name = "reports",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_report_reporter_target",
               columnNames = {"reporter_id", "target_type", "target_id"}))
@Getter
@NoArgsConstructor
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 신고한 사용자 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    /** 신고 대상 유형 (POST / COMMENT) */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    private ReportTargetType targetType;

    /** 신고 대상 ID (post.id 또는 comment.id) */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** 신고 사유 */
    @Column(nullable = false)
    private String reason;

    /** 처리 상태 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportStatus status = ReportStatus.PENDING;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime resolvedAt;

    @Builder
    public Report(User reporter, ReportTargetType targetType, Long targetId, String reason) {
        this.reporter   = reporter;
        this.targetType = targetType;
        this.targetId   = targetId;
        this.reason     = reason;
        this.createdAt  = LocalDateTime.now();
    }

    public void resolve() {
        this.status     = ReportStatus.RESOLVED;
        this.resolvedAt = LocalDateTime.now();
    }

    public void dismiss() {
        this.status     = ReportStatus.DISMISSED;
        this.resolvedAt = LocalDateTime.now();
    }
}

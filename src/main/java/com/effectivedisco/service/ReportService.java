package com.effectivedisco.service;

import com.effectivedisco.domain.Report;
import com.effectivedisco.domain.ReportStatus;
import com.effectivedisco.domain.ReportTargetType;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.ReportRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final UserRepository   userRepository;

    /* ── 신고 접수 ──────────────────────────────────────────────── */

    /**
     * 게시물 신고.
     * 같은 사용자가 동일 게시물을 이미 신고한 경우 예외 발생.
     */
    @Transactional
    public void reportPost(String reporterUsername, Long postId, String reason) {
        report(reporterUsername, ReportTargetType.POST, postId, reason);
    }

    /**
     * 댓글 신고.
     * 같은 사용자가 동일 댓글을 이미 신고한 경우 예외 발생.
     */
    @Transactional
    public void reportComment(String reporterUsername, Long commentId, String reason) {
        report(reporterUsername, ReportTargetType.COMMENT, commentId, reason);
    }

    private void report(String reporterUsername, ReportTargetType type, Long targetId, String reason) {
        User reporter = userRepository.findByUsername(reporterUsername)
                .orElseThrow(() -> new UsernameNotFoundException(reporterUsername));

        if (reportRepository.existsByReporterAndTargetTypeAndTargetId(reporter, type, targetId)) {
            throw new IllegalStateException("이미 신고한 항목입니다.");
        }

        reportRepository.save(Report.builder()
                .reporter(reporter)
                .targetType(type)
                .targetId(targetId)
                .reason(reason)
                .build());
    }

    /* ── 관리자: 처리 ───────────────────────────────────────────── */

    /** 미처리(PENDING) 신고 목록을 접수 시간순으로 반환 */
    public List<Report> getPendingReports() {
        return reportRepository.findByStatusOrderByCreatedAtAsc(ReportStatus.PENDING);
    }

    /** 신고 처리: 조치 완료(RESOLVED) */
    @Transactional
    public void resolve(Long reportId) {
        findReport(reportId).resolve();
    }

    /** 신고 기각: 문제 없음(DISMISSED) */
    @Transactional
    public void dismiss(Long reportId) {
        findReport(reportId).dismiss();
    }

    /** 헤더/대시보드 배지용 미처리 신고 건수 */
    public long getPendingCount() {
        return reportRepository.countByStatus(ReportStatus.PENDING);
    }

    private Report findReport(Long id) {
        return reportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("신고를 찾을 수 없습니다: " + id));
    }
}

package com.effectivedisco.repository;

import com.effectivedisco.domain.Report;
import com.effectivedisco.domain.ReportStatus;
import com.effectivedisco.domain.ReportTargetType;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportRepository extends JpaRepository<Report, Long> {

    /** 중복 신고 방지: 같은 대상을 이미 신고했는지 확인 */
    boolean existsByReporterAndTargetTypeAndTargetId(
            User reporter, ReportTargetType targetType, Long targetId);

    /** 관리자 패널: 처리 상태별 신고 목록 (접수 시간순) */
    List<Report> findByStatusOrderByCreatedAtAsc(ReportStatus status);

    /** 헤더 배지: 미처리(PENDING) 신고 건수 */
    long countByStatus(ReportStatus status);

    /** 회원 탈퇴: 이 사용자가 제출한 신고 전체 삭제 */
    void deleteByReporter(User reporter);
}

package com.effectivedisco.repository;

import com.effectivedisco.domain.Report;
import com.effectivedisco.domain.ReportStatus;
import com.effectivedisco.domain.ReportTargetType;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReportRepository extends JpaRepository<Report, Long> {

    /** 중복 신고 방지: 같은 대상을 이미 신고했는지 확인 */
    boolean existsByReporterAndTargetTypeAndTargetId(
            User reporter, ReportTargetType targetType, Long targetId);

    /** 관리자 패널: 처리 상태별 신고 목록 (접수 시간순) — reporter를 함께 fetch */
    @Query("SELECT r FROM Report r JOIN FETCH r.reporter WHERE r.status = :status ORDER BY r.createdAt ASC")
    List<Report> findByStatusWithReporterOrderByCreatedAtAsc(@Param("status") ReportStatus status);

    /**
     * 처리 완료된 신고 이력 조회 (RESOLVED + DISMISSED) — reporter를 함께 fetch.
     * resolvedAt 역순(최신 처리 순)으로 반환한다.
     */
    @Query("SELECT r FROM Report r JOIN FETCH r.reporter WHERE r.status IN :statuses ORDER BY r.resolvedAt DESC")
    List<Report> findByStatusInWithReporterOrderByResolvedAtDesc(@Param("statuses") List<ReportStatus> statuses);

    /** 헤더 배지: 미처리(PENDING) 신고 건수 */
    long countByStatus(ReportStatus status);

    /** 회원 탈퇴: 이 사용자가 제출한 신고 전체 삭제 */
    void deleteByReporter(User reporter);
}

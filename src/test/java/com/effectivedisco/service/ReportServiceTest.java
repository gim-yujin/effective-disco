package com.effectivedisco.service;

import com.effectivedisco.domain.Report;
import com.effectivedisco.domain.ReportStatus;
import com.effectivedisco.domain.ReportTargetType;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.ReportRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock ReportRepository reportRepository;
    @Mock UserRepository   userRepository;

    @InjectMocks ReportService reportService;

    // ── reportPost ────────────────────────────────────────────

    @Test
    void reportPost_success_savesReport() {
        User reporter = makeUser("reporter");
        given(userRepository.findByUsername("reporter")).willReturn(Optional.of(reporter));
        given(reportRepository.existsByReporterAndTargetTypeAndTargetId(
                reporter, ReportTargetType.POST, 1L)).willReturn(false);

        reportService.reportPost("reporter", 1L, "스팸입니다");

        verify(reportRepository).save(any(Report.class));
    }

    @Test
    void reportPost_duplicate_throwsIllegalStateException() {
        User reporter = makeUser("reporter");
        given(userRepository.findByUsername("reporter")).willReturn(Optional.of(reporter));
        given(reportRepository.existsByReporterAndTargetTypeAndTargetId(
                reporter, ReportTargetType.POST, 1L)).willReturn(true);

        assertThatThrownBy(() -> reportService.reportPost("reporter", 1L, "중복"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 신고한 항목");
    }

    // ── reportComment ──────────────────────────────────────────

    @Test
    void reportComment_success_savesReport() {
        User reporter = makeUser("reporter");
        given(userRepository.findByUsername("reporter")).willReturn(Optional.of(reporter));
        given(reportRepository.existsByReporterAndTargetTypeAndTargetId(
                reporter, ReportTargetType.COMMENT, 5L)).willReturn(false);

        reportService.reportComment("reporter", 5L, "욕설");

        verify(reportRepository).save(any(Report.class));
    }

    // ── resolve / dismiss ─────────────────────────────────────

    @Test
    void resolve_success_changesStatusToResolved() {
        Report report = makeReport(10L, ReportStatus.PENDING);
        given(reportRepository.findById(10L)).willReturn(Optional.of(report));

        reportService.resolve(10L);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(report.getResolvedAt()).isNotNull();
    }

    @Test
    void dismiss_success_changesStatusToDismissed() {
        Report report = makeReport(10L, ReportStatus.PENDING);
        given(reportRepository.findById(10L)).willReturn(Optional.of(report));

        reportService.dismiss(10L);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.DISMISSED);
        assertThat(report.getResolvedAt()).isNotNull();
    }

    @Test
    void resolve_notFound_throwsException() {
        given(reportRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.resolve(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("신고를 찾을 수 없습니다");
    }

    // ── getPendingCount ───────────────────────────────────────

    @Test
    void getPendingCount_returnsDelegatedValue() {
        given(reportRepository.countByStatus(ReportStatus.PENDING)).willReturn(7L);

        assertThat(reportService.getPendingCount()).isEqualTo(7L);
    }

    // ── getPendingReports ─────────────────────────────────────

    @Test
    void getPendingReports_returnsListFromRepository() {
        Report r = makeReport(1L, ReportStatus.PENDING);
        given(reportRepository.findByStatusWithReporterOrderByCreatedAtAsc(ReportStatus.PENDING))
                .willReturn(List.of(r));

        assertThat(reportService.getPendingReports()).hasSize(1);
    }

    // ── helpers ───────────────────────────────────────────────

    private User makeUser(String username) {
        return User.builder().username(username).email(username + "@test.com").password("pw").build();
    }

    private Report makeReport(Long id, ReportStatus status) {
        User reporter = makeUser("reporter");
        Report report = Report.builder()
                .reporter(reporter)
                .targetType(ReportTargetType.POST)
                .targetId(1L)
                .reason("사유")
                .build();
        ReflectionTestUtils.setField(report, "id", id);
        ReflectionTestUtils.setField(report, "status", status);
        return report;
    }
}

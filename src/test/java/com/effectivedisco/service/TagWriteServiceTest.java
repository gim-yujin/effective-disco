package com.effectivedisco.service;

import com.effectivedisco.domain.Tag;
import com.effectivedisco.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * TagWriteService 단위 테스트.
 *
 * 태그 생성 시 동시성 충돌(duplicate key)을 흡수하는 로직을 검증한다.
 * PlatformTransactionManager를 mock하여 REQUIRES_NEW 트랜잭션 내부 동작을 재현한다.
 */
@ExtendWith(MockitoExtension.class)
class TagWriteServiceTest {

    @Mock TagRepository tagRepository;
    @Mock PlatformTransactionManager transactionManager;

    TagWriteService tagWriteService;

    @BeforeEach
    void setUp() {
        tagWriteService = new TagWriteService(tagRepository, transactionManager);

        // TransactionTemplate이 내부에서 getTransaction/commit을 호출하므로 mock 설정
        given(transactionManager.getTransaction(any()))
                .willReturn(new SimpleTransactionStatus());
    }

    // ── ensureTagsExist: 정상 케이스 ─────────────────────

    @Test
    void ensureTagsExist_savesEachMissingTag() {
        Set<String> missing = new LinkedHashSet<>(Set.of("java", "spring"));

        // saveAndFlush가 정상적으로 동작하는 경우
        given(tagRepository.saveAndFlush(any(Tag.class)))
                .willAnswer(inv -> inv.getArgument(0));

        tagWriteService.ensureTagsExist(missing);

        // 각 태그마다 saveAndFlush가 호출되어야 한다
        verify(tagRepository, times(2)).saveAndFlush(any(Tag.class));
    }

    // ── ensureTagsExist: duplicate key 흡수 ──────────────

    @Test
    void ensureTagsExist_ignoresDuplicateKeyViolation() {
        Set<String> missing = new LinkedHashSet<>(Set.of("java"));

        // SQL state 23505 = duplicate key violation
        SQLException sqlCause = new SQLException("duplicate key", "23505");
        DataIntegrityViolationException duplicateEx =
                new DataIntegrityViolationException("duplicate", sqlCause);

        // 첫 번째 saveAndFlush 호출에서 duplicate key 예외 발생
        given(tagRepository.saveAndFlush(any(Tag.class))).willThrow(duplicateEx);

        // TransactionTemplate은 예외 시 rollback을 호출한다 — 여기서는 상태만 설정
        doAnswer(inv -> null).when(transactionManager).rollback(any(TransactionStatus.class));

        // duplicate key는 조용히 무시되어야 한다 (예외가 밖으로 전파되지 않음)
        tagWriteService.ensureTagsExist(missing);
    }

    // ── ensureTagsExist: 다른 DataIntegrityViolation은 전파 ─

    @Test
    void ensureTagsExist_propagatesNonDuplicateDataIntegrityViolation() {
        Set<String> missing = new LinkedHashSet<>(Set.of("java"));

        // "duplicate key"가 아닌 다른 제약 조건 위반
        DataIntegrityViolationException otherEx =
                new DataIntegrityViolationException("check constraint violated");

        given(tagRepository.saveAndFlush(any(Tag.class))).willThrow(otherEx);

        // duplicate key가 아닌 예외는 그대로 전파되어야 한다
        assertThatThrownBy(() -> tagWriteService.ensureTagsExist(missing))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("check constraint");
    }
}

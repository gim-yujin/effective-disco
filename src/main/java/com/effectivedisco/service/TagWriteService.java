package com.effectivedisco.service;

import com.effectivedisco.domain.Tag;
import com.effectivedisco.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/**
 * 문제 해결:
 * 게시물 작성이 동시에 들어오면 같은 새 태그를 여러 트랜잭션이 같이 saveAll() 하며
 * unique(tags.name) 충돌이 broad mixed 전체 실패로 번질 수 있다.
 * 태그 생성만 REQUIRES_NEW 로 분리해 duplicate 를 흡수하면 게시물 작성 트랜잭션은 그대로 진행할 수 있다.
 */
@Service
@RequiredArgsConstructor
public class TagWriteService {

    private static final String DUPLICATE_KEY_SQL_STATE = "23505";

    private final TagRepository tagRepository;
    private final PlatformTransactionManager transactionManager;

    public void ensureTagsExist(Set<String> missingTagNames) {
        for (String missingTagName : missingTagNames) {
            try {
                createTagInNewTransaction(missingTagName);
            } catch (DuplicateTagRaceIgnoredException ignored) {
                // 다른 트랜잭션이 같은 태그를 먼저 만든 경우다. 아래 최종 재조회에서 합쳐진다.
            }
        }
    }

    private void createTagInNewTransaction(String tagName) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactionTemplate.executeWithoutResult(status -> {
            try {
                tagRepository.saveAndFlush(new Tag(tagName));
            } catch (DataIntegrityViolationException exception) {
                if (isDuplicateKeyViolation(exception)) {
                    status.setRollbackOnly();
                    throw new DuplicateTagRaceIgnoredException(tagName, exception);
                }
                throw exception;
            }
        });
    }

    private boolean isDuplicateKeyViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && DUPLICATE_KEY_SQL_STATE.equals(sqlException.getSQLState())) {
                return true;
            }

            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("duplicate key")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class DuplicateTagRaceIgnoredException extends RuntimeException {
        private DuplicateTagRaceIgnoredException(String tagName, Throwable cause) {
            super("duplicate tag race ignored: " + tagName, cause);
        }
    }
}

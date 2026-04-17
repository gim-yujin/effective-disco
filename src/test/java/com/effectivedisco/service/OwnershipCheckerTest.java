package com.effectivedisco.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnershipCheckerTest {

    @Test
    void check_sameUser_doesNotThrow() {
        assertThatNoException()
                .isThrownBy(() -> OwnershipChecker.check("alice", "alice"));
    }

    @Test
    void check_differentUser_throwsAccessDeniedException() {
        assertThatThrownBy(() -> OwnershipChecker.check("alice", "bob"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("권한");
    }
}

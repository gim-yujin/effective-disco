package com.effectivedisco.service;

import com.effectivedisco.domain.Block;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.BlockRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BlockServiceTest {

    @Mock BlockRepository blockRepository;
    @Mock UserRepository  userRepository;

    @InjectMocks BlockService blockService;

    // ── block / unblock ───────────────────────────────────

    @Test
    void block_notBlocking_createsBlock() {
        User blocker = makeUser("alice");
        User blocked = makeUser("bob");

        given(userRepository.findByUsernameForUpdate("alice")).willReturn(Optional.of(blocker));
        given(userRepository.findByUsername("bob")).willReturn(Optional.of(blocked));
        given(blockRepository.existsByBlockerAndBlocked(blocker, blocked)).willReturn(false);

        blockService.block("alice", "bob");

        verify(blockRepository).save(any(Block.class));
    }

    @Test
    void block_alreadyBlocking_isIdempotent() {
        User blocker = makeUser("alice");
        User blocked = makeUser("bob");

        given(userRepository.findByUsernameForUpdate("alice")).willReturn(Optional.of(blocker));
        given(userRepository.findByUsername("bob")).willReturn(Optional.of(blocked));
        given(blockRepository.existsByBlockerAndBlocked(blocker, blocked)).willReturn(true);

        blockService.block("alice", "bob");

        verify(blockRepository, never()).save(any());
    }

    @Test
    void block_selfBlock_throwsIllegalArgumentException() {
        // 자기 자신을 차단하려 할 때 예외 발생 — repository 호출 없음
        assertThatThrownBy(() -> blockService.block("alice", "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("자기 자신을 차단할 수 없습니다");

        verify(blockRepository, never()).save(any());
    }

    @Test
    void unblock_existingBlock_deletesRelation() {
        User blocker = makeUser("alice");
        User blocked = makeUser("bob");

        given(userRepository.findByUsernameForUpdate("alice")).willReturn(Optional.of(blocker));
        given(userRepository.findByUsername("bob")).willReturn(Optional.of(blocked));
        given(blockRepository.deleteByBlockerAndBlocked(blocker, blocked)).willReturn(1L);

        blockService.unblock("alice", "bob");

        verify(blockRepository).deleteByBlockerAndBlocked(blocker, blocked);
    }

    @Test
    void unblock_missingBlock_isIdempotent() {
        User blocker = makeUser("alice");
        User blocked = makeUser("bob");

        given(userRepository.findByUsernameForUpdate("alice")).willReturn(Optional.of(blocker));
        given(userRepository.findByUsername("bob")).willReturn(Optional.of(blocked));
        given(blockRepository.deleteByBlockerAndBlocked(blocker, blocked)).willReturn(0L);

        blockService.unblock("alice", "bob");

        verify(blockRepository).deleteByBlockerAndBlocked(blocker, blocked);
    }

    // ── isBlocking ────────────────────────────────────────

    @Test
    void isBlocking_blockExists_returnsTrue() {
        User blocker = makeUser("alice");
        User blocked = makeUser("bob");

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(blocker));
        given(userRepository.findByUsername("bob")).willReturn(Optional.of(blocked));
        given(blockRepository.existsByBlockerAndBlocked(blocker, blocked)).willReturn(true);

        assertThat(blockService.isBlocking("alice", "bob")).isTrue();
    }

    @Test
    void isBlocking_noBlock_returnsFalse() {
        User blocker = makeUser("alice");
        User blocked = makeUser("bob");

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(blocker));
        given(userRepository.findByUsername("bob")).willReturn(Optional.of(blocked));
        given(blockRepository.existsByBlockerAndBlocked(blocker, blocked)).willReturn(false);

        assertThat(blockService.isBlocking("alice", "bob")).isFalse();
    }

    // ── getBlockedUsernames ───────────────────────────────

    @Test
    void getBlockedUsernames_returnsSetOfBlockedUsernames() {
        User alice = makeUser("alice");
        User bob   = makeUser("bob");
        User carol = makeUser("carol");

        // alice가 bob과 carol을 차단 중
        Block b1 = new Block(alice, bob);
        Block b2 = new Block(alice, carol);

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(alice));
        given(blockRepository.findByBlockerOrderByCreatedAtDesc(alice)).willReturn(List.of(b1, b2));

        Set<String> result = blockService.getBlockedUsernames("alice");

        assertThat(result).containsExactlyInAnyOrder("bob", "carol");
    }

    @Test
    void getBlockedUsernames_noBlocks_returnsEmptySet() {
        User alice = makeUser("alice");

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(alice));
        given(blockRepository.findByBlockerOrderByCreatedAtDesc(alice)).willReturn(List.of());

        assertThat(blockService.getBlockedUsernames("alice")).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────

    private User makeUser(String username) {
        return User.builder()
                .username(username)
                .email(username + "@test.com")
                .password("encoded")
                .build();
    }
}

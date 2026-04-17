package com.effectivedisco.service;

import com.effectivedisco.domain.User;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class UserLookupServiceTest {

    @Mock UserRepository userRepository;

    @InjectMocks UserLookupService userLookupService;

    @Test
    void findByUsername_exists_returnsUser() {
        User user = makeUser("alice");
        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));

        User result = userLookupService.findByUsername("alice");

        assertThat(result.getUsername()).isEqualTo("alice");
    }

    @Test
    void findByUsername_notFound_throwsUsernameNotFoundException() {
        given(userRepository.findByUsername("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> userLookupService.findByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void findByUsernameForUpdate_exists_returnsUser() {
        User user = makeUser("bob");
        given(userRepository.findByUsernameForUpdate("bob")).willReturn(Optional.of(user));

        User result = userLookupService.findByUsernameForUpdate("bob");

        assertThat(result.getUsername()).isEqualTo("bob");
    }

    @Test
    void findByUsernameForUpdate_notFound_throwsUsernameNotFoundException() {
        given(userRepository.findByUsernameForUpdate("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> userLookupService.findByUsernameForUpdate("ghost"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("ghost");
    }

    private User makeUser(String username) {
        return User.builder()
                .username(username)
                .email(username + "@test.com")
                .password("encoded")
                .build();
    }
}

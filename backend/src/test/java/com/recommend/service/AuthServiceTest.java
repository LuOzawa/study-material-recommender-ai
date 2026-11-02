package com.recommend.service;

import com.recommend.dto.LoginRequest;
import com.recommend.dto.RegisterRequest;
import com.recommend.dto.ResetPasswordRequest;
import com.recommend.entity.User;
import com.recommend.repository.UserRepository;
import com.recommend.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private UserDetailsService userDetailsService;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    @Test
    void registerTrimsUsernameHashesPasswordAndNormalizesEmail() {
        when(userRepository.existsByUsernameIgnoreCase("alice")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret1")).thenReturn("bcrypt-hash");

        RegisterRequest request = new RegisterRequest();
        request.setUsername("  alice  ");
        request.setPassword("secret1");
        request.setEmail("  alice@example.com  ");
        request.setStudyPhase("INTENSIVE");

        authService.register(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getPassword()).isEqualTo("bcrypt-hash");
        assertThat(saved.getEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getStudyPhase()).isEqualTo(User.StudyPhase.INTENSIVE);
    }

    @Test
    void registerRejectsCaseInsensitiveDuplicateUsername() {
        when(userRepository.existsByUsernameIgnoreCase("Alice")).thenReturn(true);

        RegisterRequest request = new RegisterRequest();
        request.setUsername("Alice");
        request.setPassword("secret1");

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户名已存在");
        verify(userRepository, never()).save(any());
    }

    @Test
    void loginReturnsTokenAndCanonicalUsername() {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setPassword("$2a$10$hash");
        user.setRole(User.Role.USER);

        UserDetails details = org.springframework.security.core.userdetails.User
                .withUsername("alice").password("$2a$10$hash").authorities("ROLE_USER").build();
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(details);
        when(jwtUtil.generateToken(details)).thenReturn("jwt-token");
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest();
        request.setUsername("  alice ");
        request.setPassword("secret1");

        Map<String, Object> result = authService.login(request);

        assertThat(result.get("token")).isEqualTo("jwt-token");
        assertThat(result.get("username")).isEqualTo("alice");
        assertThat(result.get("role")).isEqualTo(User.Role.USER);
    }

    @Test
    void resetPasswordRejectsEmailMismatch() {
        User user = new User();
        user.setUsername("bob");
        user.setEmail("bob@example.com");
        when(userRepository.findByUsernameIgnoreCase("bob")).thenReturn(Optional.of(user));

        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setUsername("bob");
        request.setEmail("other@example.com");
        request.setNewPassword("secret1");

        assertThatThrownBy(() -> authService.resetPassword(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户名与邮箱不匹配");
    }
}

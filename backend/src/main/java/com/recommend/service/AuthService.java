package com.recommend.service;

import com.recommend.dto.LoginRequest;
import com.recommend.dto.RegisterRequest;
import com.recommend.dto.ResetPasswordRequest;
import com.recommend.entity.User;
import com.recommend.repository.UserRepository;
import com.recommend.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    public Map<String, Object> login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

        UserDetails userDetails = userDetailsService.loadUserByUsername(request.getUsername());
        String token = jwtUtil.generateToken(userDetails);

        User user = userRepository.findByUsername(request.getUsername()).orElseThrow();
        upgradeLegacyPassword(user, request.getPassword());

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("username", user.getUsername());
        result.put("role", user.getRole());
        result.put("userId", user.getId());
        return result;
    }

    public void register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("用户名已存在");
        }

        String normalizedEmail = normalizeEmail(request.getEmail());
        if (normalizedEmail != null && userRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalArgumentException("邮箱已注册");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        // 注意：必须经过 PasswordEncoder，切勿直接存明文
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(normalizedEmail);
        user.setTargetSchool(request.getTargetSchool());
        user.setMajor(request.getMajor());
        if (request.getStudyPhase() != null) {
            user.setStudyPhase(User.StudyPhase.valueOf(request.getStudyPhase()));
        }
        userRepository.save(user);
    }

    public void resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        String userEmail = user.getEmail();
        if (userEmail == null || request.getEmail() == null || !userEmail.equalsIgnoreCase(request.getEmail().trim())) {
            throw new IllegalArgumentException("用户名与邮箱不匹配");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    /**
     * 历史遗留账号的密码可能是早年以明文写入的（注册接口曾直接存密文）。
     * 登录成功后将明文口令就地升级为 BCrypt，无需用户改密码。
     */
    private void upgradeLegacyPassword(User user, String rawPassword) {
        String stored = user.getPassword();
        if (stored == null || stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) {
            return;
        }
        try {
            user.setPassword(passwordEncoder.encode(rawPassword));
            userRepository.save(user);
            log.info("Upgraded legacy plaintext password to BCrypt for user={}", user.getUsername());
        } catch (Exception e) {
            log.warn("Failed to upgrade legacy password for user={}: {}", user.getUsername(), e.getMessage());
        }
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

package com.recommend.config;

import com.recommend.security.JwtFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final UserDetailsService userDetailsService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/materials", "/api/materials/{id}").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/materials/*/comments").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/materials/*/like").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/materials/*/favorite-status").permitAll()
                .requestMatchers("/api/categories").permitAll()
                .requestMatchers("/api/recommendations/public").permitAll()
                .requestMatchers("/api/internal/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new PasswordEncoder() {
            private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder bcrypt =
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

            @Override
            public String encode(CharSequence rawPassword) {
                // 一律 BCrypt。早期版本此处直接返回明文，属于严重缺陷，已修正。
                return rawPassword == null ? null : bcrypt.encode(rawPassword);
            }

            @Override
            public boolean matches(CharSequence rawPassword, String storedPassword) {
                if (rawPassword == null || storedPassword == null) {
                    return false;
                }
                String raw = rawPassword.toString();
                if (isBcryptHash(storedPassword)) {
                    return bcrypt.matches(raw, storedPassword);
                }
                // 兼容历史明文口令（仅为了能登录，登录后 AuthService 会自动升级为 BCrypt）。
                // 用常量时间比较，避免通过响应时间猜口令。
                return java.security.MessageDigest.isEqual(
                        raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        storedPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            private boolean isBcryptHash(String value) {
                return value.startsWith("$2a$")
                        || value.startsWith("$2b$")
                        || value.startsWith("$2y$");
            }
        };
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}

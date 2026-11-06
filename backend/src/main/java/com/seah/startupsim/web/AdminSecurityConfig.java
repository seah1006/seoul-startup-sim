package com.seah.startupsim.web;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;


/** 관리자만 세션 인증으로 보호하고 공개 게임 API의 인증 책임은 분리한다. */
@Configuration
public class AdminSecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(AdminSecurityConfig.class);

    @Bean
    UserDetailsService adminUsers(@Value("${ADMIN_USERNAME:}") String username,
            @Value("${ADMIN_PASSWORD:}") String password) {
        InMemoryUserDetailsManager users = new InMemoryUserDetailsManager();
        if (username.isBlank() || password.isBlank()) {
            log.warn("관리자 환경 변수가 없어 관리자 기능을 비활성화합니다.");
        } else {
            users.createUser(User.withUsername(username)
                    .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password)).roles("ADMIN").build());
        }
        return users;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/admin/login", "/admin.css").permitAll()
                .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                .anyRequest().permitAll())
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
            .formLogin(form -> form.loginPage("/admin/login").loginProcessingUrl("/admin/login")
                    .defaultSuccessUrl("/admin", true).permitAll())
            .logout(logout -> logout.logoutUrl("/admin/logout").logoutSuccessUrl("/admin/login"))
            .exceptionHandling(errors -> errors
                    .authenticationEntryPoint((request, response, exception) -> {
                        if (!request.getRequestURI().startsWith("/admin/api/")) {
                            response.sendRedirect(request.getContextPath() + "/admin/login");
                            return;
                        }
                        response.setStatus(401);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"관리자 로그인이 필요합니다.\",\"timestamp\":\"" + Instant.now() + "\"}");
                    })
                    .accessDeniedHandler((request, response, exception) -> {
                        response.setStatus(403);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"접근 권한이 없거나 요청 토큰이 유효하지 않습니다.\",\"timestamp\":\"" + Instant.now() + "\"}");
                    }));
        return http.build();
    }
}




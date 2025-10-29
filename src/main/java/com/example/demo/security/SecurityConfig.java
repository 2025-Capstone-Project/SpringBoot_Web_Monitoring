package com.example.demo.security;

import com.example.demo.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(UserRepository userRepository, JwtUtil jwtUtil) {
        return new JwtAuthenticationFilter(userRepository, jwtUtil);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .headers(h -> h.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)) // H2 콘솔용
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers("/.well-known/**").permitAll() // 확장프로그램/브라우저 well-known 허용
                        .requestMatchers("/").permitAll()
                        .requestMatchers("/user/register", "/user/login").permitAll()
                        .requestMatchers("/user/api/register", "/user/api/login").permitAll()
                        .requestMatchers("/css/**", "/js/**").permitAll()
                        .requestMatchers("/favicon.ico", "/favicon.svg").permitAll() // 파비콘 로딩 허용
                        // WebSocket(SockJS) 핸드셰이크 허용
                        .requestMatchers("/ws-endpoint/**").permitAll()
                        // Q&A는 목록/조회 GET 허용
                        .requestMatchers(HttpMethod.GET, "/qa/**").permitAll()
                        .requestMatchers("/qa/**").authenticated()
                        // 화면용 세션 인증 경로
                        .requestMatchers("/web/**", "/fan").authenticated()
                        // API는 인증 필요(JWT 필터가 없는 토큰 차단)
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/user/login"))
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout.logoutUrl("/user/logout").logoutSuccessUrl("/user/login?logout"));

        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

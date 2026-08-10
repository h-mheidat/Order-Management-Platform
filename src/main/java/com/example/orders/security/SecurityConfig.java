package com.example.orders.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Stage 1 placeholder filter chain.
 *
 * <p>There is no authentication yet - no users, no JWT issuing, no roles. That is stage 3. What
 * this class establishes now is the <em>posture</em>: the API is stateless and every route is
 * closed until a later stage opens it deliberately.
 *
 * <p>{@code denyAll()} rather than {@code permitAll()} is the point. A permissive placeholder that
 * someone forgets to tighten ships an open API; a restrictive one fails closed, and the failure is
 * a 403 in development rather than a data leak in production. Endpoints get opened one at a time,
 * with intent, as the stages that own them land.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // No cookies and no server-side session, so there is no session-riding attack for
                // CSRF tokens to defend against. This is only valid as long as authentication
                // stays header-based (stage 3: bearer JWT) - it must be revisited if a cookie is
                // ever introduced.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Browser login UI has no place in a token API. Left enabled, they would answer an
                // unauthenticated call with a 302 to /login instead of a 401.
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        // Liveness/readiness must stay reachable: an orchestrator cannot
                        // authenticate. Only health is public - /metrics and the rest of the
                        // actuator surface are not.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                // 401 instead of a redirect for anonymous callers.
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(Customizer.withDefaults())
                .build();
    }
}

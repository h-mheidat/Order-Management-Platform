package com.example.orders.security;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A separate filter chain for the actuator.
 *
 * <p>This exists because the API chain ends in {@code denyAll()}, which is correct for business
 * endpoints and wrong for the two endpoints that infrastructure has to reach. Prometheus cannot present
 * a bearer token, and an orchestrator's liveness probe cannot log in - so with one chain covering
 * everything, either the API fails open or monitoring gets 401s. Splitting them lets each have the rule
 * it needs.
 *
 * <p>Ordered before the API chain, and matched by {@code EndpointRequest} rather than a URL pattern.
 * That matters because {@code application-prod.yml} moves the actuator to its own port: a
 * {@code /actuator/**} pattern would still work, but it would also match a business endpoint someone
 * later mounts under that path, and it would not follow a change to {@code management.endpoints.web.base-path}.
 *
 * <h2>What "permitAll" means here, and what it relies on</h2>
 *
 * <p>Health and the Prometheus scrape are open. That is only acceptable because the actuator listens on
 * its own port in production, which is reachable from inside the cluster and never published through the
 * ingress - the network is the boundary, not authentication. If that port is ever exposed publicly, this
 * becomes an information leak: metrics reveal traffic volumes, error rates and internal endpoint names.
 * Everything else is denied outright, so heapdump, env, configprops and loggers stay unreachable even if
 * someone widens the exposure list by accident.
 */
@Configuration
public class ActuatorSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class,
                                PrometheusScrapeEndpoint.class)).permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}

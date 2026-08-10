package com.example.orders.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.JWSAlgorithm;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Authentication and authorization for the API.
 *
 * <p>Stage 4 replaces the stage 1 placeholder. The posture is unchanged - stateless, no browser
 * login, {@code denyAll()} as the final rule - but requests now carry a bearer JWT, and endpoints are
 * opened one at a time.
 *
 * <p>The actuator is not handled here at all - see {@code ActuatorSecurityConfig}, which owns an
 * earlier-ordered chain for it. Infrastructure cannot authenticate, so those endpoints need a different
 * rule than the {@code denyAll()} that ends this one.
 *
 * <p>Two layers of authorization, on purpose:
 * <ul>
 *   <li><b>URL rules here</b> answer "may this role reach this endpoint at all". Coarse, and applied
 *       before a controller is even selected.
 *   <li><b>{@code @PreAuthorize} and ownership checks in the service layer</b> answer "may this
 *       specific caller touch this specific order". A URL rule cannot express that: every CUSTOMER
 *       may call {@code GET /api/orders/{id}}, but only for their own orders.
 * </ul>
 * Relying on URL rules alone is how one customer ends up reading another's order by changing the id.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    @org.springframework.core.annotation.Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                            RestAuthenticationEntryPoint authenticationEntryPoint,
                                            RestAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        return http
                // No cookie and no session, so there is no session for a cross-site request to ride.
                // Valid only while authentication stays header-based - revisit if a cookie appears.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        // Registration and login must be reachable without a token.
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        // The OpenAPI document and Swagger UI. Open, because the whole point is to be
                        // readable before you have a token - you need the spec to discover how to get
                        // one. Safe only because springdoc is switched off entirely under the prod
                        // profile: these matchers then guard paths that do not exist, rather than
                        // publishing the API surface. If docs are ever wanted in a deployed
                        // environment, they belong behind the ingress or on the management port, not
                        // here.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // Statistics are ADMIN-only and enforced twice - here and with
                        // @PreAuthorize on the method - so neither one alone is load-bearing.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Per-order authorization needs the order's owner, which is only known
                        // after loading it. Decided in the service layer, not here.
                        .requestMatchers("/api/orders/**").authenticated()
                        // Anything not listed above is closed. New endpoints must be opened
                        // deliberately; a forgotten route fails shut.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    /**
     * Validates incoming tokens.
     *
     * <p>Symmetric HS256: the same secret signs and verifies. Correct for a single application, and
     * the thing to change when this monolith is split - at that point every service holding the
     * verification key could also mint tokens, so issuing moves to an RS256 private key and the
     * others verify with the public one.
     *
     * <p>Issuer validation is explicit. Signature alone only proves "signed with our key"; without
     * checking {@code iss}, a token minted by any other system sharing the secret would be accepted.
     */
    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        SecretKeySpec key = new SecretKeySpec(
                properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.from(
                        JWSAlgorithm.HS256.getName()))
                .build();
        // Default validators cover exp and nbf; this adds iss.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /**
     * Maps the {@code roles} claim onto Spring Security authorities.
     *
     * <p>The default converter reads {@code scope}/{@code scp} and prefixes {@code SCOPE_}, which
     * would make every {@code hasRole('ADMIN')} check silently false - the classic symptom being a
     * 403 for a user who demonstrably has the role.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * BCrypt, via the delegating encoder so hashes are stored with an {@code {bcrypt}} prefix.
     *
     * <p>That prefix is what makes a future algorithm migration possible: old hashes keep verifying
     * with their original algorithm while new ones use the new default. A bare {@code BCryptPasswordEncoder}
     * stores no prefix, and changing algorithm then invalidates every existing password.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}

package com.example.orders.support;

import java.util.UUID;

import com.example.orders.entity.Role;
import com.example.orders.entity.User;
import com.example.orders.repository.UserRepository;
import com.example.orders.security.TokenService;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates users with a given role and hands back a real access token for them.
 *
 * <p>Tokens are minted through the application's own {@link TokenService} rather than hand-built, so
 * tests exercise the same signing, claims and issuer the production path uses. A hand-crafted token
 * would let a test pass against a decoder that rejects every real token.
 *
 * <p>SUPPORT and ADMIN accounts have to be created directly: registration deliberately only ever
 * produces a CUSTOMER, which is the property {@code AuthFlowIT} asserts.
 */
@TestComponent
public class TestUsers {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    TestUsers(UserRepository userRepository, PasswordEncoder passwordEncoder,
              TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    /** A brand new user in the given role, and a token for them. */
    @Transactional
    public String tokenFor(Role role) {
        return tokenService.issue(create(role));
    }

    @Transactional
    public User create(Role role) {
        // Unique per call: the container is shared across test classes, so a fixed username would
        // collide with whatever a previous class left behind.
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        User user = new User(role.name().toLowerCase() + "_" + suffix,
                suffix + "@test.example", passwordEncoder.encode("Password123"), role);
        return userRepository.saveAndFlush(user);
    }
}

package com.example.orders.service;

import com.example.orders.dto.LoginRequest;
import com.example.orders.dto.RegisterRequest;
import com.example.orders.dto.TokenResponse;
import com.example.orders.dto.UserResponse;
import com.example.orders.entity.Role;
import com.example.orders.entity.User;
import com.example.orders.exception.ConflictException;
import com.example.orders.exception.InvalidCredentialsException;
import com.example.orders.mapper.UserMapper;
import com.example.orders.repository.UserRepository;
import com.example.orders.security.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and login.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final UserMapper userMapper;

    AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                TokenService tokenService, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.userMapper = userMapper;
    }

    /**
     * Registers a new CUSTOMER.
     *
     * <p>The uniqueness checks below are a courtesy, not the guarantee: between the check and the
     * insert, another request can register the same email. The real protection is
     * {@code uq_users_email_lower} in the database, so the insert is also guarded - the checks exist
     * only to turn the common case into a clear 409 instead of a constraint violation.
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw ConflictException.emailAlreadyUsed();
        }
        if (userRepository.existsByUsername(request.username())) {
            throw ConflictException.usernameAlreadyUsed();
        }

        // The role is not taken from the request - see RegisterRequest.
        User user = new User(request.username(), request.email(),
                passwordEncoder.encode(request.password()), Role.CUSTOMER);

        try {
            User saved = userRepository.saveAndFlush(user);
            log.info("Registered user id={} role={}", saved.getId(), saved.getRole());
            return userMapper.toResponse(saved);
        } catch (DataIntegrityViolationException e) {
            // Lost the race described above. Which constraint fired is not worth unpicking here:
            // either way the caller must pick different details.
            throw ConflictException.emailAlreadyUsed();
        }
    }

    /**
     * Verifies credentials and issues an access token.
     *
     * <p>Read-only: login writes nothing. There is no "last login" update precisely because it would
     * turn every login into a write and a row lock on a hot record.
     */
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> {
                    // Hash a dummy value anyway, so a nonexistent email does not answer measurably
                    // faster than a wrong password. Without this, response timing alone reveals
                    // which addresses have accounts.
                    passwordEncoder.matches(request.password(), DUMMY_HASH);
                    return new InvalidCredentialsException();
                });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.info("Failed login for user id={}", user.getId());
            throw new InvalidCredentialsException();
        }
        if (!user.isEnabled()) {
            // Same error as a wrong password: "this account exists but is disabled" is information
            // an unauthenticated caller has not earned.
            log.info("Login attempt on disabled user id={}", user.getId());
            throw new InvalidCredentialsException();
        }

        log.info("Issued token for user id={} role={}", user.getId(), user.getRole());
        return TokenResponse.bearer(tokenService.issue(user), tokenService.expiresInSeconds());
    }

    /** A structurally valid BCrypt hash of a value nobody knows, used only for timing equalisation. */
    private static final String DUMMY_HASH =
            "{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
}

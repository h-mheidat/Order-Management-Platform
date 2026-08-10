package com.example.orders.controller;

import com.example.orders.dto.LoginRequest;
import com.example.orders.dto.RegisterRequest;
import com.example.orders.dto.TokenResponse;
import com.example.orders.dto.UserResponse;
import com.example.orders.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication endpoints.
 *
 * <p>Thin by design: bind, validate, delegate. No business rules live here, so they cannot be
 * bypassed by any other caller of the service.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 201 with the created user - never with a token. Registering is not authenticating. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}

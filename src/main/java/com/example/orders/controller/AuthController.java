package com.example.orders.controller;

import com.example.orders.dto.LoginRequest;
import com.example.orders.dto.RegisterRequest;
import com.example.orders.dto.TokenResponse;
import com.example.orders.dto.UserResponse;
import com.example.orders.dto.ErrorResponse;
import com.example.orders.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Authentication", description = "Registration and token issuing. No token required.")
// Overrides the document-wide bearer requirement: these two endpoints are how you obtain a token, so
// requiring one would be circular. Without this, Swagger UI shows a padlock on both and callers assume
// they cannot register.
@SecurityRequirements
class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 201 with the created user - never with a token. Registering is not authenticating. */
    @Operation(summary = "Register a new customer",
            description = """
                    Always creates a CUSTOMER. A `role` field in the request body is ignored - it is not
                    part of the payload, so it cannot be used to self-promote.

                    Returns the created user and **no token**: registering is not authenticating. Call
                    `/api/auth/login` next.

                    Email is stored lower-cased and is unique case-insensitively, so
                    `Ahmad@test.com` and `ahmad@test.com` are the same account.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Validation failed. `fieldErrors` lists "
                    + "every rejected field, not just the first.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_USED or USERNAME_ALREADY_USED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @Operation(summary = "Log in and get a bearer token",
            description = """
                    Returns a JWT valid for 15 minutes. Paste it into **Authorize** above.

                    An unknown email and a wrong password produce an identical response, deliberately:
                    distinguishing them would turn this endpoint into a way to discover which addresses
                    have accounts.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token issued"),
            @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))})
    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}

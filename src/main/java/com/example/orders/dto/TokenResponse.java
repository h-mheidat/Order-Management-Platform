package com.example.orders.dto;

/**
 * Issued access token.
 *
 * @param accessToken the signed JWT
 * @param tokenType   always {@code Bearer}, so clients can build the header without hardcoding it
 * @param expiresIn   lifetime in seconds, letting a client refresh before expiry rather than
 *                    discovering it through a failed request
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

    public static TokenResponse bearer(String accessToken, long expiresIn) {
        return new TokenResponse(accessToken, "Bearer", expiresIn);
    }
}

package com.example.orders.security;

import com.example.orders.entity.Role;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The caller, read out of the validated JWT.
 *
 * <p>A small named type rather than passing {@link Jwt} around: the service layer should not have to
 * know that identity arrives as a token, or which claim holds the role. Swapping JWT for sessions
 * later would touch this class and nothing behind it.
 *
 * @param id   the user id, from the {@code sub} claim
 * @param role the caller's role, from the {@code roles} claim
 */
public record AuthenticatedUser(Long id, Role role) {

    /**
     * Reads a caller out of a validated token.
     *
     * <p>Safe to parse without defensive checks on the signature: by the time a {@link Jwt} reaches
     * application code, the resource server filter has already verified signature, issuer and
     * expiry. An unparseable subject means we issued a malformed token, which is a bug here.
     */
    public static AuthenticatedUser from(Jwt jwt) {
        Long id = Long.valueOf(jwt.getSubject());
        java.util.List<String> roles = jwt.getClaimAsStringList(TokenService.ROLES_CLAIM);
        if (roles == null || roles.isEmpty()) {
            throw new IllegalStateException("Token has no roles claim");
        }
        return new AuthenticatedUser(id, Role.valueOf(roles.getFirst()));
    }

    /** Whether this caller may act on data belonging to {@code ownerId}. */
    public boolean owns(Long ownerId) {
        return id.equals(ownerId);
    }

    public boolean isStaff() {
        return role == Role.SUPPORT || role == Role.ADMIN;
    }
}

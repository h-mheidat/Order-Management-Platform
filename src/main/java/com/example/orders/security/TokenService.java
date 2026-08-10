package com.example.orders.security;

import java.time.Instant;
import java.util.List;

import com.example.orders.entity.User;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

/**
 * Mints signed access tokens.
 *
 * <p>Claims are kept to the minimum a request needs to be authorized: who the caller is, and what
 * they may do. Nothing else - a JWT is signed, not encrypted, so every claim is readable by anyone
 * holding the token.
 */
@Service
public class TokenService {

    /** Claim carrying the caller's roles. Read back by the JWT authentication converter. */
    static final String ROLES_CLAIM = "roles";

    private final JwtProperties properties;
    private final JWSSigner signer;

    TokenService(JwtProperties properties) throws JOSEException {
        this.properties = properties;
        this.signer = new MACSigner(properties.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Issues an access token for a user.
     *
     * <p>The subject is the user id, not the email: an email can be changed, and every token issued
     * before the change would then point at a subject that no longer exists.
     */
    public String issue(User user) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(user.getId()))
                .issuer(properties.issuer())
                .issueTime(java.util.Date.from(now))
                .expirationTime(java.util.Date.from(now.plus(properties.expiration())))
                .claim("email", user.getEmail())
                .claim(ROLES_CLAIM, List.of(user.getRole().name()))
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            // Signing cannot fail for a validated key, so this is a configuration fault, not a
            // per-request condition worth modelling as a checked exception.
            throw new IllegalStateException("Unable to sign access token", e);
        }
        return jwt.serialize();
    }

    /** How long issued tokens last - returned to clients so they can refresh before expiry. */
    public long expiresInSeconds() {
        return properties.expiration().toSeconds();
    }
}

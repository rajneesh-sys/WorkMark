package com.workmark.security;

import com.workmark.auth.entity.UserRole;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private static final String SECRET = "unit-test-secret-that-is-at-least-32-bytes-long";

    @Test
    void validTokenWithIssuerAndAudienceIsAccepted() {
        JwtService jwt = service(3600);
        CurrentUser user = new CurrentUser(UUID.randomUUID(), UUID.randomUUID(), null,
                "user@example.com", "hash", UserRole.EMPLOYEE, 0);

        String token = jwt.issue(user);

        assertEquals(user.getId().toString(), jwt.parse(token).getSubject());
        assertEquals("workmark", jwt.parse(token).getIssuer());
    }

    @Test
    void missingIssuerIsRejected() {
        JwtService jwt = service(3600);
        String token = unsignedClaimsToken(null, "workmark-api");

        assertThrows(JwtException.class, () -> jwt.parse(token));
    }

    @Test
    void incorrectIssuerIsRejected() {
        JwtService jwt = service(3600);
        String token = unsignedClaimsToken("other", "workmark-api");

        assertThrows(JwtException.class, () -> jwt.parse(token));
    }

    @Test
    void missingAudienceIsRejected() {
        JwtService jwt = service(3600);
        String token = unsignedClaimsToken("workmark", null);

        assertThrows(JwtException.class, () -> jwt.parse(token));
    }

    @Test
    void incorrectAudienceIsRejected() {
        JwtService jwt = service(3600);
        String token = unsignedClaimsToken("workmark", "other-api");

        assertThrows(JwtException.class, () -> jwt.parse(token));
    }

    @Test
    void expiredAndIncorrectlySignedTokensAreRejected() {
        JwtService jwt = service(3600);
        String expired = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer("workmark")
                .audience().add("workmark-api").and()
                .expiration(Date.from(Instant.now().minusSeconds(1)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        String wrongSignature = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer("workmark")
                .audience().add("workmark-api").and()
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(("wrong-" + SECRET).getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(JwtException.class, () -> jwt.parse(expired));
        assertThrows(JwtException.class, () -> jwt.parse(wrongSignature));
    }

    @Test
    void invalidExpirationConfigurationPreventsConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new JwtService(SECRET, 0, "workmark", "workmark-api"));
        assertThrows(IllegalArgumentException.class,
                () -> new JwtService(SECRET, 86401, "workmark", "workmark-api"));
    }

    private JwtService service(long expiration) {
        return new JwtService(SECRET, expiration, "workmark", "workmark-api");
    }

    private String unsignedClaimsToken(String issuer, String audience) {
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)));
        if (issuer != null) builder.issuer(issuer);
        if (audience != null) builder.audience().add(audience).and();
        return builder.compact();
    }
}

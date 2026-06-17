package com.company.dlms.infrastructure.security;

import com.company.dlms.domain.security.Role;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    private static final String SECRET = "test_secret_must_be_at_least_32_characters_long_for_hs256";

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        ReflectionTestUtils.setField(jwtService, "expiration", 3600000L);
    }

    @Test
    void generateAccessToken_embedsRoleClaim() {
        String token = jwtService.generateAccessToken("user-uuid-123", Role.ENGINEER);
        StepVerifier.create(jwtService.validateToken(token))
                .assertNext(claims -> {
                    assertThat(claims.get("role", String.class)).isEqualTo("ENGINEER");
                    assertThat(claims.getSubject()).isEqualTo("user-uuid-123");
                })
                .verifyComplete();
    }

    @Test
    void validateToken_freshToken_returnsValidClaims() {
        String token = jwtService.generateAccessToken("uuid-abc", Role.VIEWER);
        StepVerifier.create(jwtService.validateToken(token))
                .assertNext(claims -> assertThat(claims.getSubject()).isEqualTo("uuid-abc"))
                .verifyComplete();
    }

    @Test
    void validateToken_expiredToken_returnsError() {
        JwtService shortLived = new JwtService();
        ReflectionTestUtils.setField(shortLived, "secret", SECRET);
        ReflectionTestUtils.setField(shortLived, "expiration", -1L); // already expired
        String token = shortLived.generateAccessToken("user-x", Role.VIEWER);

        StepVerifier.create(jwtService.validateToken(token))
                .expectError()
                .verify();
    }

    @Test
    void validateToken_tamperedToken_returnsError() {
        String token = jwtService.generateAccessToken("user-y", Role.ADMIN);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        StepVerifier.create(jwtService.validateToken(tampered))
                .expectError()
                .verify();
    }

    @Test
    void validateToken_wrongSecretToken_returnsError() {
        JwtService other = new JwtService();
        ReflectionTestUtils.setField(other, "secret", "completely_different_secret_key_value!");
        ReflectionTestUtils.setField(other, "expiration", 3600000L);
        String token = other.generateAccessToken("user-z", Role.VIEWER);

        StepVerifier.create(jwtService.validateToken(token))
                .expectError()
                .verify();
    }

    @Test
    void extractRole_returnsCorrectRole() {
        String token = jwtService.generateAccessToken("user-r", Role.ADMIN);
        Claims claims = jwtService.validateToken(token).block();
        assertThat(jwtService.extractRole(claims)).isEqualTo(Role.ADMIN);
    }
}

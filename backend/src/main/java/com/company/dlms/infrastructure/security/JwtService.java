package com.company.dlms.infrastructure.security;

import com.company.dlms.domain.security.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class JwtService {

    @Value("${dlms.security.jwt.secret}")
    private String secret;

    @Value("${dlms.security.jwt.expiration}")
    private long expiration;

    /** Generate token for a UserDetails — kept for backward compatibility with existing tests. */
    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    /** Generate access token with userId as subject and role claim embedded. */
    public String generateAccessToken(String userId, Role role) {
        return Jwts.builder()
                .subject(userId)
                .claim("role", role.name())
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Reactive token validation. Wraps synchronous JJWT parse in boundedElastic
     * to satisfy Constitution Principle I (no blocking on reactor thread).
     */
    public Mono<Claims> validateToken(String token) {
        return Mono.fromCallable(() ->
                Jwts.parser()
                        .verifyWith(getSigningKey())
                        .build()
                        .parseSignedClaims(token)
                        .getPayload()
        ).subscribeOn(Schedulers.boundedElastic());
    }

    public String extractSubject(Claims claims) {
        return claims.getSubject();
    }

    public Role extractRole(Claims claims) {
        String roleName = claims.get("role", String.class);
        return Role.fromString(roleName);
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}

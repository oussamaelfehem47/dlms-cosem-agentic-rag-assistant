package com.company.dlms.infrastructure.security;

import com.company.dlms.domain.security.User;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String AUTH_LOOKUP_ATTRIBUTE = JwtAuthFilter.class.getName() + ".authLookup";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, ObjectProvider<UserRepository> userRepositoryProvider) {
        this.jwtService = jwtService;
        this.userRepository = userRepositoryProvider.getIfAvailable();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return chain.filter(exchange);
        }

        String token = authHeader.substring(7);

        return jwtService.validateToken(token)
                .flatMap(claims -> {
                    UUID userId = UUID.fromString(jwtService.extractSubject(claims));
                    if (userRepository == null) {
                        return chain.filter(exchange)
                                .contextWrite(
                                        ReactiveSecurityContextHolder.withAuthentication(
                                                buildAuthenticationFromClaims(userId, claims)
                                        )
                                );
                    }
                    return getOrCreateAuthenticationLookup(exchange, userId)
                            .flatMap(result -> {
                                if (!result.authenticated()) {
                                    return chain.filter(exchange);
                                }
                                return chain.filter(exchange)
                                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(result.authentication()));
                            });
                })
                .onErrorResume(ex -> {
                    // Token is invalid, stale, or belongs to a deactivated/deleted user.
                    // Continue without authentication so permitAll() endpoints remain reachable
                    // and Spring Security can make the final authorization decision.
                    log.debug("JWT validation failed, continuing without auth: {}", ex.getMessage());
                    return chain.filter(exchange);
                });
    }

    @SuppressWarnings("unchecked")
    private Mono<AuthLookupResult> getOrCreateAuthenticationLookup(ServerWebExchange exchange, UUID userId) {
        Mono<AuthLookupResult> cached = exchange.getAttribute(AUTH_LOOKUP_ATTRIBUTE);
        if (cached != null) {
            return cached;
        }

        Mono<AuthLookupResult> lookup = userRepository.findByUserIdAndActiveTrue(userId)
                .map(this::buildAuthentication)
                .map(AuthLookupResult::authenticated)
                .switchIfEmpty(Mono.just(AuthLookupResult.unauthenticated()))
                .cache();
        exchange.getAttributes().put(AUTH_LOOKUP_ATTRIBUTE, lookup);
        return lookup;
    }

    private UsernamePasswordAuthenticationToken buildAuthentication(User user) {
        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority(user.role().toAuthority())
        );
        return new UsernamePasswordAuthenticationToken(user.userId().toString(), null, authorities);
    }

    private UsernamePasswordAuthenticationToken buildAuthenticationFromClaims(UUID userId, Claims claims) {
        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority(jwtService.extractRole(claims).toAuthority())
        );
        return new UsernamePasswordAuthenticationToken(userId.toString(), null, authorities);
    }

    private record AuthLookupResult(boolean authenticated, UsernamePasswordAuthenticationToken authentication) {
        private static AuthLookupResult authenticated(UsernamePasswordAuthenticationToken authentication) {
            return new AuthLookupResult(true, authentication);
        }

        private static AuthLookupResult unauthenticated() {
            return new AuthLookupResult(false, null);
        }
    }
}

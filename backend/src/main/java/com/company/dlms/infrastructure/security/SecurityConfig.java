package com.company.dlms.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .addFilterAt(jwtAuthFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(exchanges -> exchanges
                        // Public endpoints — no auth required
                        .pathMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/register").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET,
                                "/api/actuator/health",
                                "/api/actuator/info",
                                "/api/actuator/prometheus").permitAll()

                        // MCP tool endpoints — internal Docker network only, no auth needed
                        .pathMatchers("/api/mcp/tools/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/mcp/health").permitAll()

                        // VIEWER + ENGINEER + ADMIN
                        .pathMatchers(HttpMethod.POST, "/api/upload", "/api/chat/stream", "/api/decode/stream", "/api/siconia/stream")
                                .hasAnyRole("VIEWER", "ENGINEER", "ADMIN")

                        // ENGINEER + ADMIN only
                        .pathMatchers(HttpMethod.POST, "/api/workflow/**")
                                .hasAnyRole("ENGINEER", "ADMIN")

                        // Any authenticated user can submit feedback (viewers click thumbs)
                        .pathMatchers(HttpMethod.POST, "/api/admin/reflection/feedback").authenticated()

                        // ADMIN only — reflection stats and review endpoints
                        .pathMatchers("/api/admin/reflection/**").hasRole("ADMIN")

                        // ADMIN only
                        .pathMatchers(HttpMethod.POST, "/api/admin/users/*/activate").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PATCH, "/api/admin/users/*/role").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.DELETE, "/api/admin/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/admin/users").hasRole("ADMIN")

                        // Any authenticated user
                        .pathMatchers("/api/auth/refresh", "/api/auth/logout").authenticated()
                        .pathMatchers("/api/conversations/**").authenticated()

                        // Deny everything else
                        .anyExchange().authenticated()
                )
                .build();
    }
}

package com.company.dlms.api;

import com.company.dlms.domain.security.Role;
import com.company.dlms.domain.security.User;
import com.company.dlms.infrastructure.security.JwtAuthFilter;
import com.company.dlms.infrastructure.security.JwtService;
import com.company.dlms.infrastructure.security.SecurityConfig;
import com.company.dlms.infrastructure.security.SuppressWwwAuthenticateFilter;
import com.company.dlms.infrastructure.security.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;

@WebFluxTest(CurrentUserController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, SuppressWwwAuthenticateFilter.class})
class CurrentUserControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private JwtService jwtService;

    private static final UUID USER_ID = UUID.randomUUID();

    private SecurityContext engineerCtx() {
        return new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_ENGINEER"))));
    }

    @Test
    void getCurrentUser_authenticated_returnsProfile() {
        User user = new User(
                USER_ID,
                "engineer@company.com",
                "engineer",
                "hash",
                Role.ENGINEER,
                true,
                Instant.parse("2026-01-01T00:00:00Z")
        );
        when(userRepository.findById(USER_ID)).thenReturn(Mono.just(user));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockAuthentication(engineerCtx().getAuthentication()))
                .get().uri("/api/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.user_id").isEqualTo(USER_ID.toString())
                .jsonPath("$.username").isEqualTo("engineer")
                .jsonPath("$.email").isEqualTo("engineer@company.com")
                .jsonPath("$.role").isEqualTo("engineer")
                .jsonPath("$.active").isEqualTo(true);
    }

    @Test
    void getCurrentUser_unauthenticated_isRejected() {
        webTestClient
                .get().uri("/api/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}

package com.company.dlms.api;

import com.company.dlms.infrastructure.security.AuditService;
import com.company.dlms.infrastructure.security.JwtAuthFilter;
import com.company.dlms.infrastructure.security.JwtService;
import com.company.dlms.infrastructure.security.SecurityConfig;
import com.company.dlms.infrastructure.security.SuppressWwwAuthenticateFilter;
import com.company.dlms.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;

@WebFluxTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, SuppressWwwAuthenticateFilter.class, GlobalExceptionHandler.class})
class AuthControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private UserService userService;

    @MockBean
    private AuditService auditService;

    @MockBean
    private JwtService jwtService;

    @Test
    void register_withoutAuthentication_isRejected() {
        webTestClient
                .post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", "new@test.com",
                        "username", "newuser",
                        "password", "Pass1234!"
                ))
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(userService);
    }
}

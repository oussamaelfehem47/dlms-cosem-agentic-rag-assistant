package com.company.dlms.api;

import com.company.dlms.domain.security.Role;
import com.company.dlms.domain.security.User;
import com.company.dlms.infrastructure.security.JwtService;
import com.company.dlms.infrastructure.security.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthControllerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg15")
            .withDatabaseName("dlms_assistant")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName());
        String r2dbcUrl = String.format("r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName());

        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.r2dbc.url", () -> r2dbcUrl);
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("dlms.security.jwt.secret", () -> "test_secret_must_be_at_least_32_characters_long_for_hs256");
        registry.add("dlms.security.session.encryption-key", () -> "dlmsassistant16c");
        registry.add("dlms.security.audit.secret", () -> "test-audit-hmac-key-at-least-32chars!!");
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:11434");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:schema.sql");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll().block();
    }

    @Test
    void register_withAdminToken_shouldCreateUser() {
        // Register an admin user directly, then use their token to register another
        User admin = userRepository.save(
                User.create("admin@company.com", "adminuser",
                        passwordEncoder.encode("AdminPass1!"), Role.ADMIN)
        ).block();
        String adminToken = jwtService.generateAccessToken(admin.userId().toString(), Role.ADMIN);

        RegisterRequest request = new RegisterRequest("testuser", "test@company.com", "Password123!", Role.VIEWER);
        webTestClient.post()
                .uri("/api/auth/register")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody(AuthResponse.class)
                .value(resp -> {
                    assertThat(resp.username()).isEqualTo("testuser");
                    assertThat(resp.role()).isEqualTo("viewer");
                    assertThat(resp.access_token()).isNotBlank();
                });
    }

    @Test
    void register_withoutToken_shouldReturn401() {
        RegisterRequest request = new RegisterRequest("testuser", "test@company.com", "Password123!", Role.VIEWER);
        webTestClient.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void register_withViewerToken_shouldReturn403() {
        User viewer = userRepository.save(
                User.create("viewer@company.com", "vieweruser",
                        passwordEncoder.encode("Pass1234!"), Role.VIEWER)
        ).block();
        String viewerToken = jwtService.generateAccessToken(viewer.userId().toString(), Role.VIEWER);

        RegisterRequest request = new RegisterRequest("other", "other@company.com", "Password123!", Role.VIEWER);
        webTestClient.post()
                .uri("/api/auth/register")
                .header("Authorization", "Bearer " + viewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void login_shouldReturnJwt() {
        User user = userRepository.save(
                User.create("login@company.com", "loginuser",
                        passwordEncoder.encode("Secret!1"), Role.ENGINEER)
        ).block();

        LoginRequest request = new LoginRequest("login@company.com", null, "Secret!1");
        webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class)
                .value(response -> {
                    assertThat(response.access_token()).isNotNull();
                    assertThat(response.username()).isEqualTo("loginuser");
                    assertThat(response.role()).isEqualTo("engineer");
                });
    }

    @Test
    void login_withInvalidCredentials_shouldReturn401() {
        LoginRequest request = new LoginRequest("nonexistent@company.com", null, "wrong");
        webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}

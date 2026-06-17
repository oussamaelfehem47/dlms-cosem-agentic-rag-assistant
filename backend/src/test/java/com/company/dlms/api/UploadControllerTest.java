package com.company.dlms.api;

import com.company.dlms.agent.RouterAgent;
import com.company.dlms.infrastructure.security.JwtAuthFilter;
import com.company.dlms.infrastructure.security.JwtService;
import com.company.dlms.infrastructure.security.SecurityConfig;
import com.company.dlms.infrastructure.security.SuppressWwwAuthenticateFilter;
import com.company.dlms.infrastructure.upload.FileUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@WebFluxTest(UploadController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, SuppressWwwAuthenticateFilter.class, FileUploadService.class, RouterAgent.class, GlobalExceptionHandler.class})
class UploadControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private JwtService jwtService;

    private SecurityContext viewerCtx() {
        return new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(
                        UUID.randomUUID().toString(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))
                )
        );
    }

    @Test
    void uploadLogFile_returnsStructuredRoutingHints() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource("""
2024-01-15 10:30:00 [PLC] [ERROR] Connection lost to meter 12345
2024-01-15 10:30:01 [WAN] [WARN] Retry attempt 1/3
2024-01-15 10:30:05 [PLC] [INFO] Reconnection successful
""".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "sample.log";
            }
        }).contentType(MediaType.TEXT_PLAIN);

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockAuthentication(viewerCtx().getAuthentication()))
                .post()
                .uri("/api/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.input_class").isEqualTo("log_block")
                .jsonPath("$.suggested_endpoint").isEqualTo("siconia")
                .jsonPath("$.text").value(value -> org.assertj.core.api.Assertions.assertThat(value.toString()).contains("Connection lost"));
    }
}

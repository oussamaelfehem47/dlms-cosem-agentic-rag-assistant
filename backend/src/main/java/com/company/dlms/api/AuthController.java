package com.company.dlms.api;

import com.company.dlms.infrastructure.security.AuditService;
import com.company.dlms.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final AuditService auditService;

    public AuthController(UserService userService, AuditService auditService) {
        this.userService = userService;
        this.auditService = auditService;
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<AuthResponse>> login(@RequestBody LoginRequest loginRequest) {
        return userService.login(loginRequest)
                .doOnSuccess(resp ->
                        auditService.log("USER_LOGIN", "auth", resp.user_id(), Map.of("username", resp.username()))
                                .subscribe())
                .map(ResponseEntity::ok);
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<AuthResponse>> register(@RequestBody RegisterRequest registerRequest) {
        return userService.register(registerRequest)
                .doOnSuccess(resp ->
                        auditService.log("USER_REGISTER", "auth", resp.user_id(), Map.of("username", resp.username()))
                                .subscribe())
                .map(resp -> ResponseEntity.status(201).body(resp));
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<AuthResponse>> refresh(@RequestBody RefreshRequest refreshRequest) {
        return userService.refresh(refreshRequest.refresh_token())
                .doOnSuccess(resp ->
                        auditService.log("TOKEN_REFRESH", "auth", resp.user_id(), Map.of())
                                .subscribe())
                .map(ResponseEntity::ok);
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .flatMap(auth -> {
                    String userId = (String) auth.getPrincipal();
                    UUID uid = UUID.fromString(userId);
                    auditService.log("USER_LOGOUT", "auth", uid, Map.of()).subscribe();
                    return userService.logout(uid);
                })
                .then(Mono.just(ResponseEntity.<Void>noContent().build()));
    }

    public record RefreshRequest(String refresh_token) {}
}

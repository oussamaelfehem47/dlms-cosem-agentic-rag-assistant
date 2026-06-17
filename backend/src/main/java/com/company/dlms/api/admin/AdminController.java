package com.company.dlms.api.admin;

import com.company.dlms.domain.security.Role;
import com.company.dlms.domain.security.User;
import com.company.dlms.infrastructure.security.AuditService;
import com.company.dlms.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserService userService;
    private final AuditService auditService;

    public AdminController(UserService userService, AuditService auditService) {
        this.userService = userService;
        this.auditService = auditService;
    }

    @GetMapping("/users")
    public Flux<UserSummaryResponse> listUsers() {
        return userService.listUsers().map(UserSummaryResponse::from);
    }

    @DeleteMapping("/users/{id}")
    public Mono<ResponseEntity<Void>> deactivateUser(@PathVariable UUID id) {
        return currentUserId()
                .flatMap(requesterId -> userService.deactivateUser(id, requesterId)
                        .then(auditService.log(
                                "USER_DEACTIVATE",
                                "admin",
                                requesterId,
                                Map.of("targetUserId", id.toString())
                        ))
                        .thenReturn(ResponseEntity.<Void>noContent().build()));
    }

    @PostMapping("/users/{id}/activate")
    public Mono<ResponseEntity<UserSummaryResponse>> activateUser(@PathVariable UUID id) {
        return currentUserId()
                .flatMap(requesterId -> userService.activateUser(id)
                        .flatMap(updatedUser -> auditService.log(
                                        "USER_ACTIVATE",
                                        "admin",
                                        requesterId,
                                        Map.of("targetUserId", id.toString()))
                                .thenReturn(updatedUser)))
                .map(updatedUser -> ResponseEntity.ok(UserSummaryResponse.from(updatedUser)));
    }

    @PatchMapping("/users/{id}/role")
    public Mono<ResponseEntity<UserSummaryResponse>> updateUserRole(
            @PathVariable UUID id,
            @RequestBody UpdateUserRoleRequest request
    ) {
        return currentUserId()
                .flatMap(requesterId -> userService.updateUserRole(id, requesterId, request.role())
                        .flatMap(updatedUser -> auditService.log(
                                        "USER_ROLE_UPDATE",
                                        "admin",
                                        requesterId,
                                        Map.of(
                                                "targetUserId", id.toString(),
                                                "newRole", updatedUser.role().name()
                                        ))
                                .thenReturn(updatedUser)))
                .map(updatedUser -> ResponseEntity.ok(UserSummaryResponse.from(updatedUser)));
    }

    @DeleteMapping("/users/{id}/hard")
    public Mono<ResponseEntity<Void>> hardDeleteUser(@PathVariable UUID id) {
        return currentUserId()
                .flatMap(requesterId -> userService.hardDeleteUser(id, requesterId)
                        .then(auditService.log(
                                "USER_HARD_DELETE",
                                "admin",
                                requesterId,
                                Map.of("targetUserId", id.toString())
                        ))
                        .thenReturn(ResponseEntity.<Void>noContent().build()));
    }

    private Mono<UUID> currentUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> UUID.fromString((String) ctx.getAuthentication().getPrincipal()));
    }

    public record UpdateUserRoleRequest(Role role) {}

    public record UserSummaryResponse(
            UUID userId,
            String username,
            String email,
            String role,
            boolean active,
            Instant createdAt
    ) {
        static UserSummaryResponse from(User user) {
            return new UserSummaryResponse(
                    user.userId(),
                    user.username(),
                    user.email(),
                    user.role().name().toLowerCase(),
                    user.active(),
                    user.createdAt()
            );
        }
    }
}

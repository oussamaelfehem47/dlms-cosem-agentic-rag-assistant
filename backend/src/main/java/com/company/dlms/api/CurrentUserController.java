package com.company.dlms.api;

import com.company.dlms.infrastructure.security.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
public class CurrentUserController {

    private final UserRepository userRepository;

    public CurrentUserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/api/me")
    public Mono<ResponseEntity<CurrentUserProfileResponse>> getCurrentUser() {
        return currentUserId()
                .flatMap(userRepository::findById)
                .switchIfEmpty(Mono.error(new ResponseStatusException(UNAUTHORIZED, "User not found")))
                .map(user -> ResponseEntity.ok(new CurrentUserProfileResponse(
                        user.userId(),
                        user.username(),
                        user.email(),
                        user.role().name().toLowerCase(),
                        user.createdAt(),
                        user.active()
                )));
    }

    private Mono<UUID> currentUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> UUID.fromString((String) ctx.getAuthentication().getPrincipal()));
    }
}

package com.company.dlms.infrastructure.security;

import com.company.dlms.domain.security.User;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.UUID;

@Repository
public interface UserRepository extends ReactiveCrudRepository<User, UUID> {
    Mono<User> findByEmail(String email);
    Mono<User> findByUsername(String username);
    Flux<User> findAllByActiveTrue();
    Mono<User> findByUserIdAndActiveTrue(UUID userId);

    @Query("SELECT COUNT(*) FROM users WHERE UPPER(role) = UPPER(:role)")
    Mono<Long> countByRoleName(String role);
}

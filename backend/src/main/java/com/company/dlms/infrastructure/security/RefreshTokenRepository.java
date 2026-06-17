package com.company.dlms.infrastructure.security;

import com.company.dlms.domain.security.RefreshToken;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends ReactiveCrudRepository<RefreshToken, UUID> {
    Mono<RefreshToken> findByTokenHash(String tokenHash);
    Mono<Void> deleteByUserId(UUID userId);
}

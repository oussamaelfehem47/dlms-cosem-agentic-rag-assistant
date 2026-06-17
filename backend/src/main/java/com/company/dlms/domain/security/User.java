package com.company.dlms.domain.security;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;
import java.util.UUID;

@Table("users")
public record User(
    @Id UUID userId,
    String email,
    String username,
    String passwordHash,
    Role role,
    boolean active,
    Instant createdAt
) {
    public static User create(String email, String username, String passwordHash, Role role) {
        return new User(null, email, username, passwordHash, role, true, Instant.now());
    }
}

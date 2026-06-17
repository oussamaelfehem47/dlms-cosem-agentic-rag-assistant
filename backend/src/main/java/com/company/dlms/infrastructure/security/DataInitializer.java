package com.company.dlms.infrastructure.security;

import com.company.dlms.domain.security.Role;
import com.company.dlms.domain.security.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        seedUser("admin@company.com", "admin", "admin123", Role.ADMIN);
        seedUser("engineer@company.com", "engineer", "engineer123", Role.ENGINEER);
    }

    private void seedUser(String email, String username, String plainPassword, Role role) {
        try {
            String hash = passwordEncoder.encode(plainPassword);
            User existing = userRepository.findByEmail(email).block();
            if (existing != null) {
                // Update password hash to ensure known credentials
                // User record: (userId, email, username, passwordHash, role, active, createdAt)
                User updated = new User(
                        existing.userId(),
                        existing.email(),
                        existing.username(),
                        hash,
                        existing.role(),
                        existing.active(),
                        existing.createdAt()
                );
                userRepository.save(updated).block();
                log.info("Updated password for {} (email={})", username, email);
                return;
            }

            User newUser = User.create(email, username, hash, role);
            userRepository.save(newUser).block();
            log.info("Seeded {} user (email={}, password={})", role, email, plainPassword);
        } catch (Exception e) {
            log.error("Failed to seed user {}", email, e);
        }
    }
}

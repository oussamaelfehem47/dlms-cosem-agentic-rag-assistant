package com.company.dlms.service;

import com.company.dlms.api.AuthResponse;
import com.company.dlms.api.LoginRequest;
import com.company.dlms.api.RegisterRequest;
import com.company.dlms.domain.Conversation;
import com.company.dlms.domain.security.RefreshToken;
import com.company.dlms.domain.security.Role;
import com.company.dlms.domain.security.User;
import com.company.dlms.infrastructure.db.ConversationRepository;
import com.company.dlms.infrastructure.db.MessageRepository;
import com.company.dlms.infrastructure.reflection.MessageFeedbackRepository;
import com.company.dlms.infrastructure.security.JwtService;
import com.company.dlms.infrastructure.security.RefreshTokenRepository;
import com.company.dlms.infrastructure.security.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageFeedbackRepository messageFeedbackRepository;
    private final DatabaseClient databaseClient;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final long refreshExpirationMs;

    public UserService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            MessageFeedbackRepository messageFeedbackRepository,
            DatabaseClient databaseClient,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            @Value("${dlms.security.jwt.refresh-expiration}") long refreshExpirationMs
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.messageFeedbackRepository = messageFeedbackRepository;
        this.databaseClient = databaseClient;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public Mono<AuthResponse> register(RegisterRequest req) {
        if (req == null || isBlank(req.email()) || isBlank(req.username()) || isBlank(req.password())) {
            return Mono.error(new ResponseStatusException(BAD_REQUEST, "Missing required registration fields"));
        }
        if (req.password().length() < 8) {
            return Mono.error(new ResponseStatusException(BAD_REQUEST, "Password must be at least 8 characters"));
        }

        Role role = req.role() != null ? req.role() : Role.ENGINEER;

        return userRepository.findByEmail(req.email().trim().toLowerCase())
                .flatMap(existing -> Mono.<AuthResponse>error(new ResponseStatusException(BAD_REQUEST, "Email already exists")))
                .switchIfEmpty(
                        userRepository.findByUsername(req.username().trim())
                                .flatMap(existing -> Mono.<AuthResponse>error(new ResponseStatusException(BAD_REQUEST, "Username already exists")))
                                .switchIfEmpty(
                                        userRepository.save(User.create(
                                                req.email().trim().toLowerCase(),
                                                req.username().trim(),
                                                passwordEncoder.encode(req.password()),
                                                role
                                        )).flatMap(this::issueTokens)
                                )
                );
    }

    public Mono<AuthResponse> login(LoginRequest req) {
        String identifier = req != null ? req.resolvedIdentifier() : null;
        if (isBlank(identifier) || isBlank(req.password())) {
            return Mono.error(new ResponseStatusException(BAD_REQUEST, "Email/username and password are required"));
        }

        String id = identifier.trim();
        Mono<User> lookup = id.contains("@")
                ? userRepository.findByEmail(id.toLowerCase())
                : userRepository.findByUsername(id).switchIfEmpty(userRepository.findByEmail(id.toLowerCase()));

        return lookup
                .switchIfEmpty(Mono.error(new ResponseStatusException(UNAUTHORIZED, "Invalid credentials")))
                .flatMap(user -> {
                    if (!user.active()) {
                        return Mono.error(new ResponseStatusException(UNAUTHORIZED, "Invalid credentials"));
                    }
                    if (!passwordEncoder.matches(req.password(), user.passwordHash())) {
                        return Mono.error(new ResponseStatusException(UNAUTHORIZED, "Invalid credentials"));
                    }
                    return issueTokens(user);
                });
    }

    public Mono<AuthResponse> refresh(String refreshToken) {
        if (isBlank(refreshToken)) {
            return Mono.error(new ResponseStatusException(BAD_REQUEST, "refresh_token is required"));
        }

        String tokenHash = sha256(refreshToken);
        return refreshTokenRepository.findByTokenHash(tokenHash)
                .switchIfEmpty(Mono.error(new ResponseStatusException(UNAUTHORIZED, "Invalid refresh token")))
                .flatMap(stored -> {
                    if (stored.revoked() || stored.expiresAt().isBefore(Instant.now())) {
                        return refreshTokenRepository.deleteById(stored.tokenId())
                                .then(Mono.error(new ResponseStatusException(UNAUTHORIZED, "Refresh token expired or revoked")));
                    }
                    return userRepository.findById(stored.userId())
                            .switchIfEmpty(Mono.error(new ResponseStatusException(UNAUTHORIZED, "User not found")))
                            .flatMap(user -> {
                                if (!user.active()) {
                                    return Mono.error(new ResponseStatusException(UNAUTHORIZED, "Account deactivated"));
                                }
                                return refreshTokenRepository.deleteById(stored.tokenId())
                                        .then(issueTokens(user));
                            });
                });
    }

    public Mono<Void> logout(UUID userId) {
        return refreshTokenRepository.deleteByUserId(userId);
    }

    public Flux<User> listUsers() {
        return userRepository.findAll()
                .sort(Comparator
                        .comparing(User::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(User::username, String.CASE_INSENSITIVE_ORDER));
    }

    @Transactional
    public Mono<Void> deactivateUser(UUID targetId, UUID requesterId) {
        if (targetId.equals(requesterId)) {
            return Mono.error(new ResponseStatusException(BAD_REQUEST, "Cannot deactivate your own account"));
        }

        return findUserOrThrow(targetId)
                .flatMap(user -> {
                    if (!user.active()) {
                        return Mono.just(user);
                    }
                    return userRepository.save(withActiveState(user, false));
                })
                .then();
    }

    @Transactional
    public Mono<User> activateUser(UUID targetId) {
        return findUserOrThrow(targetId)
                .flatMap(user -> {
                    if (user.active()) {
                        return Mono.just(user);
                    }
                    return userRepository.save(withActiveState(user, true));
                });
    }

    @Transactional
    public Mono<User> updateUserRole(UUID targetId, UUID requesterId, Role newRole) {
        if (newRole == null) {
            return Mono.error(new ResponseStatusException(BAD_REQUEST, "Role is required"));
        }
        if (targetId.equals(requesterId)) {
            return Mono.error(new ResponseStatusException(BAD_REQUEST, "Cannot change your own role"));
        }

        return findUserOrThrow(targetId)
                .flatMap(user -> {
                    if (user.role() == newRole) {
                        return Mono.just(user);
                    }
                    return userRepository.save(withRole(user, newRole));
                });
    }

    @Transactional
    public Mono<Void> hardDeleteUser(UUID targetId, UUID requesterId) {
        if (targetId.equals(requesterId)) {
            return Mono.error(new ResponseStatusException(BAD_REQUEST, "Cannot delete your own account"));
        }

        return findUserOrThrow(targetId)
                .flatMap(this::ensureNotLastAdmin)
                .flatMap(user -> collectSessionIds(user.userId())
                        .collectList()
                        .flatMap(sessionIds -> deleteUserArtifacts(user.userId())
                                .then(refreshTokenRepository.deleteByUserId(user.userId()))
                                .then(deleteStmEntries(sessionIds))
                                .then(userRepository.deleteById(user.userId()))));
    }

    private Mono<Void> deleteUserArtifacts(UUID userId) {
        return conversationRepository.findByUserId(userId)
                .concatMap(this::deleteConversationArtifacts)
                .then(messageFeedbackRepository.deleteByUserId(userId));
    }

    private Flux<String> collectSessionIds(UUID userId) {
        return databaseClient.sql("""
                SELECT DISTINCT m.session_id
                FROM messages m
                JOIN conversations c ON c.conversation_id = m.conversation_id
                WHERE c.user_id = :userId
                  AND m.session_id IS NOT NULL
                  AND m.session_id <> ''
                """)
                .bind("userId", userId)
                .map((row, metadata) -> row.get("session_id", String.class))
                .all();
    }

    private Mono<Void> deleteStmEntries(List<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(sessionIds)
                .concatMap(sessionId -> databaseClient.sql("DELETE FROM stm_entries WHERE session_id = :sessionId")
                        .bind("sessionId", sessionId)
                        .fetch()
                        .rowsUpdated())
                .then();
    }

    private Mono<Void> deleteConversationArtifacts(Conversation conversation) {
        return messageFeedbackRepository.deleteByConversationId(conversation.conversationId())
                .then(messageRepository.deleteByConversationId(conversation.conversationId()))
                .then(conversationRepository.deleteById(conversation.conversationId()));
    }

    private Mono<User> ensureNotLastAdmin(User user) {
        if (user.role() != Role.ADMIN) {
            return Mono.just(user);
        }

        return userRepository.countByRoleName(Role.ADMIN.name())
                .flatMap(adminCount -> {
                    if (adminCount <= 1) {
                        return Mono.error(new ResponseStatusException(BAD_REQUEST, "Cannot delete the last admin user"));
                    }
                    return Mono.just(user);
                });
    }

    private Mono<User> findUserOrThrow(UUID targetId) {
        return userRepository.findById(targetId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(NOT_FOUND, "User not found")));
    }

    private User withActiveState(User user, boolean active) {
        return new User(
                user.userId(),
                user.email(),
                user.username(),
                user.passwordHash(),
                user.role(),
                active,
                user.createdAt()
        );
    }

    private User withRole(User user, Role role) {
        return new User(
                user.userId(),
                user.email(),
                user.username(),
                user.passwordHash(),
                role,
                user.active(),
                user.createdAt()
        );
    }

    private Mono<AuthResponse> issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user.userId().toString(), user.role());
        String refreshValue = UUID.randomUUID().toString();
        String tokenHash = sha256(refreshValue);
        Instant refreshExpiry = Instant.now().plusMillis(refreshExpirationMs);

        return refreshTokenRepository.deleteByUserId(user.userId())
                .then(refreshTokenRepository.save(RefreshToken.create(user.userId(), tokenHash, refreshExpiry)))
                .map(saved -> new AuthResponse(
                        accessToken,
                        refreshValue,
                        user.userId(),
                        user.username(),
                        user.role().name().toLowerCase()
                ));
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private UserDetails toUserDetails(User user) {
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.username())
                .password(user.passwordHash())
                .authorities(List.of(new SimpleGrantedAuthority(user.role().toAuthority())))
                .build();
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}

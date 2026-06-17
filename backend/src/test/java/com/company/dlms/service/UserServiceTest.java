package com.company.dlms.service;

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
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private MessageFeedbackRepository messageFeedbackRepository;
    @Mock private DatabaseClient databaseClient;
    @Mock private JwtService jwtService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private UserService userService;

    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                refreshTokenRepository,
                conversationRepository,
                messageRepository,
                messageFeedbackRepository,
                databaseClient,
                passwordEncoder,
                jwtService,
                2592000000L
        );
    }

    @Test
    void login_validCredentials_returnsAuthResponse() {
        User user = User.create("u@test.com", "testuser", passwordEncoder.encode("Pass1234!"), Role.ENGINEER);
        User savedUser = new User(USER_ID, user.email(), user.username(), user.passwordHash(), user.role(), true, user.createdAt());

        when(userRepository.findByEmail("u@test.com")).thenReturn(Mono.just(savedUser));
        when(jwtService.generateAccessToken(anyString(), any(Role.class))).thenReturn("access.token.here");
        when(refreshTokenRepository.deleteByUserId(any())).thenReturn(Mono.empty());
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(userService.login(new LoginRequest("u@test.com", null, "Pass1234!")))
                .assertNext(resp -> {
                    assertThat(resp.access_token()).isEqualTo("access.token.here");
                    assertThat(resp.username()).isEqualTo("testuser");
                    assertThat(resp.role()).isEqualTo("engineer");
                })
                .verifyComplete();
    }

    @Test
    void register_duplicateEmail_returnsExplicitBadRequest() {
        User existing = new User(USER_ID, "u@test.com", "existing", "hash", Role.ENGINEER, true, Instant.now());
        when(userRepository.findByEmail("u@test.com")).thenReturn(Mono.just(existing));
        when(userRepository.findByUsername("newuser")).thenReturn(Mono.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(userService.register(new RegisterRequest("newuser", "u@test.com", "Pass1234!", null)))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST
                        && "Email already exists".equals(rse.getReason()))
                .verify();
    }

    @Test
    void register_duplicateUsername_returnsExplicitBadRequest() {
        User existing = new User(USER_ID, "existing@test.com", "testuser", "hash", Role.ENGINEER, true, Instant.now());
        when(userRepository.findByEmail("new@test.com")).thenReturn(Mono.empty());
        when(userRepository.findByUsername("testuser")).thenReturn(Mono.just(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(userService.register(new RegisterRequest("testuser", "new@test.com", "Pass1234!", null)))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST
                        && "Username already exists".equals(rse.getReason()))
                .verify();
    }

    @Test
    void login_wrongPassword_returns401() {
        User user = User.create("u@test.com", "testuser", passwordEncoder.encode("CorrectPass1!"), Role.VIEWER);
        User savedUser = new User(USER_ID, user.email(), user.username(), user.passwordHash(), user.role(), true, user.createdAt());

        when(userRepository.findByEmail("u@test.com")).thenReturn(Mono.just(savedUser));

        StepVerifier.create(userService.login(new LoginRequest("u@test.com", null, "WrongPass!")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == UNAUTHORIZED)
                .verify();
    }

    @Test
    void login_inactiveUser_returns401() {
        User inactiveUser = new User(USER_ID, "u@test.com", "testuser",
                passwordEncoder.encode("Pass1234!"), Role.VIEWER, false, Instant.now());
        when(userRepository.findByEmail("u@test.com")).thenReturn(Mono.just(inactiveUser));

        StepVerifier.create(userService.login(new LoginRequest("u@test.com", null, "Pass1234!")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == UNAUTHORIZED)
                .verify();
    }

    @Test
    void refresh_validToken_returnsNewAuthResponse() {
        String rawToken = UUID.randomUUID().toString();
        String hash = UserService.sha256(rawToken);
        RefreshToken stored = RefreshToken.create(USER_ID, hash, Instant.now().plusSeconds(3600));
        RefreshToken storedWithId = new RefreshToken(UUID.randomUUID(), USER_ID, hash, stored.expiresAt(), false, stored.createdAt());

        User user = new User(USER_ID, "u@test.com", "testuser",
                passwordEncoder.encode("x"), Role.ENGINEER, true, Instant.now());

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Mono.just(storedWithId));
        when(userRepository.findById(USER_ID)).thenReturn(Mono.just(user));
        when(refreshTokenRepository.deleteById(any(UUID.class))).thenReturn(Mono.empty());
        when(refreshTokenRepository.deleteByUserId(any())).thenReturn(Mono.empty());
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(jwtService.generateAccessToken(anyString(), any(Role.class))).thenReturn("new.access.token");

        StepVerifier.create(userService.refresh(rawToken))
                .assertNext(resp -> assertThat(resp.access_token()).isEqualTo("new.access.token"))
                .verifyComplete();
    }

    @Test
    void refresh_invalidToken_returns401() {
        String rawToken = "nonexistent-token";
        String hash = UserService.sha256(rawToken);

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Mono.empty());

        StepVerifier.create(userService.refresh(rawToken))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == UNAUTHORIZED)
                .verify();
    }

    @Test
    void logout_callsDeleteByUserId() {
        when(refreshTokenRepository.deleteByUserId(USER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(userService.logout(USER_ID))
                .verifyComplete();

        verify(refreshTokenRepository).deleteByUserId(USER_ID);
    }

    @Test
    void listUsers_returnsActiveAndInactiveUsers() {
        User active = new User(UUID.randomUUID(), "a@test.com", "active", "hash", Role.VIEWER, true, Instant.parse("2026-06-03T10:00:00Z"));
        User inactive = new User(UUID.randomUUID(), "b@test.com", "inactive", "hash", Role.ENGINEER, false, Instant.parse("2026-06-02T10:00:00Z"));
        when(userRepository.findAll()).thenReturn(Flux.just(inactive, active));

        StepVerifier.create(userService.listUsers())
                .assertNext(u -> {
                    assertThat(u.username()).isEqualTo("active");
                    assertThat(u.active()).isTrue();
                })
                .assertNext(u -> {
                    assertThat(u.username()).isEqualTo("inactive");
                    assertThat(u.active()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void deactivateUser_self_returnsBadRequest() {
        StepVerifier.create(userService.deactivateUser(USER_ID, USER_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST)
                .verify();
    }

    @Test
    void deactivateUser_other_savesWithActiveFalse() {
        UUID targetId = UUID.randomUUID();
        User user = new User(targetId, "t@test.com", "target", "hash", Role.VIEWER, true, Instant.now());
        when(userRepository.findById(targetId)).thenReturn(Mono.just(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(userService.deactivateUser(targetId, USER_ID))
                .verifyComplete();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().active()).isFalse();
    }

    @Test
    void activateUser_other_savesWithActiveTrue() {
        UUID targetId = UUID.randomUUID();
        User user = new User(targetId, "t@test.com", "target", "hash", Role.VIEWER, false, Instant.now());
        when(userRepository.findById(targetId)).thenReturn(Mono.just(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(userService.activateUser(targetId))
                .assertNext(updated -> assertThat(updated.active()).isTrue())
                .verifyComplete();
    }

    @Test
    void updateUserRole_self_returnsBadRequest() {
        StepVerifier.create(userService.updateUserRole(USER_ID, USER_ID, Role.ADMIN))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST
                        && "Cannot change your own role".equals(rse.getReason()))
                .verify();
    }

    @Test
    void updateUserRole_other_savesNewRole() {
        UUID targetId = UUID.randomUUID();
        User user = new User(targetId, "t@test.com", "target", "hash", Role.VIEWER, true, Instant.now());
        when(userRepository.findById(targetId)).thenReturn(Mono.just(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(userService.updateUserRole(targetId, USER_ID, Role.ENGINEER))
                .assertNext(updated -> assertThat(updated.role()).isEqualTo(Role.ENGINEER))
                .verifyComplete();
    }

    @Test
    void hardDeleteUser_self_returnsBadRequest() {
        StepVerifier.create(userService.hardDeleteUser(USER_ID, USER_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST
                        && "Cannot delete your own account".equals(rse.getReason()))
                .verify();
    }

    @Test
    void hardDeleteUser_other_deletesRelatedRecordsAndUser() {
        UUID targetId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String sessionId = "session-1";
        User user = new User(targetId, "t@test.com", "target", "hash", Role.VIEWER, false, Instant.now());
        Conversation conversation = new Conversation(conversationId, targetId, "Test", Instant.now());
        DatabaseClient.GenericExecuteSpec executeSpec = org.mockito.Mockito.mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec<Map<String, Object>> rowsFetchSpec = org.mockito.Mockito.mock(FetchSpec.class);
        DatabaseClient.GenericExecuteSpec sessionIdQuery = org.mockito.Mockito.mock(DatabaseClient.GenericExecuteSpec.class);
        DatabaseClient.GenericExecuteSpec sessionIdBound = org.mockito.Mockito.mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<String> sessionIdFetch = org.mockito.Mockito.mock(RowsFetchSpec.class);

        when(userRepository.findById(targetId)).thenReturn(Mono.just(user));
        when(conversationRepository.findByUserId(targetId)).thenReturn(Flux.just(conversation));
        when(messageFeedbackRepository.deleteByConversationId(conversationId)).thenReturn(Mono.empty());
        when(messageRepository.deleteByConversationId(conversationId)).thenReturn(Mono.empty());
        when(conversationRepository.deleteById(conversationId)).thenReturn(Mono.empty());
        when(messageFeedbackRepository.deleteByUserId(targetId)).thenReturn(Mono.empty());
        when(refreshTokenRepository.deleteByUserId(targetId)).thenReturn(Mono.empty());
        when(userRepository.deleteById(targetId)).thenReturn(Mono.empty());
        when(databaseClient.sql(org.mockito.ArgumentMatchers.contains("SELECT DISTINCT m.session_id"))).thenReturn(sessionIdQuery);
        when(sessionIdQuery.bind("userId", targetId)).thenReturn(sessionIdBound);
        when(sessionIdBound.map(org.mockito.ArgumentMatchers.<BiFunction<Row, RowMetadata, String>>any())).thenReturn(sessionIdFetch);
        when(sessionIdFetch.all()).thenReturn(Flux.just(sessionId));
        when(databaseClient.sql("DELETE FROM stm_entries WHERE session_id = :sessionId")).thenReturn(executeSpec);
        when(executeSpec.bind("sessionId", sessionId)).thenReturn(executeSpec);
        when(executeSpec.fetch()).thenReturn(rowsFetchSpec);
        when(rowsFetchSpec.rowsUpdated()).thenReturn(Mono.just(1L));

        StepVerifier.create(userService.hardDeleteUser(targetId, USER_ID))
                .verifyComplete();

        verify(messageFeedbackRepository).deleteByConversationId(conversationId);
        verify(messageRepository).deleteByConversationId(conversationId);
        verify(conversationRepository).deleteById(conversationId);
        verify(messageFeedbackRepository).deleteByUserId(targetId);
        verify(refreshTokenRepository).deleteByUserId(targetId);
        verify(userRepository).deleteById(targetId);
        verify(databaseClient).sql("DELETE FROM stm_entries WHERE session_id = :sessionId");
    }

    @Test
    void hardDeleteUser_lastAdmin_returnsBadRequest() {
        UUID targetId = UUID.randomUUID();
        User admin = new User(targetId, "admin@test.com", "lastadmin", "hash", Role.ADMIN, true, Instant.now());
        when(userRepository.findById(targetId)).thenReturn(Mono.just(admin));
        when(userRepository.countByRoleName(Role.ADMIN.name())).thenReturn(Mono.just(1L));

        StepVerifier.create(userService.hardDeleteUser(targetId, USER_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST
                        && "Cannot delete the last admin user".equals(rse.getReason()))
                .verify();
    }
}

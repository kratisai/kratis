package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.api.restdto.AuthTokensResponse;
import com.kratisai.controlplane.model.RefreshToken;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.RefreshTokenRepository;
import com.kratisai.controlplane.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EntityManager entityManager;

    @Mock
    private TeamService teamService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private UserService userService;

    private static final String REFRESH_TOKEN_VALUE = "refresh-token-value";
    private static final long REFRESH_EXPIRATION_MS = 7L * 24 * 60 * 60 * 1000;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                refreshTokenRepository,
                jwtService,
                passwordEncoder,
                entityManager,
                transactionManager,
                teamService,
                REFRESH_EXPIRATION_MS);
    }

    private static RefreshToken validToken(User user) {
        return new RefreshToken(REFRESH_TOKEN_VALUE, user, Instant.now().plusSeconds(3600));
    }

    @Test
    void refresh_validToken_shouldRotateAtomicallyAndReturnNewTokens() {
        User user = new User("test@example.com", "hash", "Test User");
        when(refreshTokenRepository.findByToken(REFRESH_TOKEN_VALUE)).thenReturn(Optional.of(validToken(user)));
        when(jwtService.isTokenValid(REFRESH_TOKEN_VALUE)).thenReturn(true);
        when(refreshTokenRepository.deleteByToken(REFRESH_TOKEN_VALUE)).thenReturn(1L);
        when(jwtService.generateAccessToken(user.getId(), user.getEmail())).thenReturn("new-access");
        when(jwtService.generateRefreshToken(user.getId(), user.getEmail())).thenReturn("new-refresh");
        when(jwtService.getAccessTokenExpiration()).thenReturn(900000L);

        AuthTokensResponse result = userService.refresh(REFRESH_TOKEN_VALUE);

        assertThat(result.accessToken()).isEqualTo("new-access");
        assertThat(result.refreshToken()).isEqualTo("new-refresh");
        assertThat(result.user().email()).isEqualTo("test@example.com");
        verify(entityManager).detach(any(RefreshToken.class));
        verify(refreshTokenRepository).deleteByToken(REFRESH_TOKEN_VALUE);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void login_shouldNotDeleteExistingRefreshTokens() {
        User user = new User("test@example.com", "hash", "Test User");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hash")).thenReturn(true);
        when(jwtService.generateAccessToken(user.getId(), user.getEmail())).thenReturn("new-access");
        when(jwtService.generateRefreshToken(user.getId(), user.getEmail())).thenReturn("new-refresh");
        when(jwtService.getAccessTokenExpiration()).thenReturn(900000L);

        AuthTokensResponse first = userService.login("test@example.com", "password123");
        assertThat(first.refreshToken()).isEqualTo("new-refresh");

        when(jwtService.generateRefreshToken(user.getId(), user.getEmail())).thenReturn("new-refresh-2");
        AuthTokensResponse second = userService.login("test@example.com", "password123");
        assertThat(second.refreshToken()).isEqualTo("new-refresh-2");

        verify(refreshTokenRepository, never()).deleteByUserId(any());
    }

    @Test
    void refresh_whenTokenAlreadyRotatedConcurrently_shouldReturn401WithoutCreatingTokens() {
        User user = new User("test@example.com", "hash", "Test User");
        when(refreshTokenRepository.findByToken(REFRESH_TOKEN_VALUE)).thenReturn(Optional.of(validToken(user)));
        when(jwtService.isTokenValid(REFRESH_TOKEN_VALUE)).thenReturn(true);
        when(refreshTokenRepository.deleteByToken(REFRESH_TOKEN_VALUE)).thenReturn(0L);

        assertThatThrownBy(() -> userService.refresh(REFRESH_TOKEN_VALUE))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(entityManager).detach(any(RefreshToken.class));
        verify(refreshTokenRepository, never()).deleteByUserId(any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void refresh_expiredToken_shouldDeleteItInNewTransactionAndReturn401() {
        User user = new User("test@example.com", "hash", "Test User");
        RefreshToken expired =
                new RefreshToken(REFRESH_TOKEN_VALUE, user, Instant.now().minusSeconds(1));
        when(refreshTokenRepository.findByToken(REFRESH_TOKEN_VALUE)).thenReturn(Optional.of(expired));
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        assertThatThrownBy(() -> userService.refresh(REFRESH_TOKEN_VALUE))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(entityManager).detach(expired);
        verify(refreshTokenRepository).deleteByToken(REFRESH_TOKEN_VALUE);
        verify(transactionManager).commit(any(TransactionStatus.class));
        verify(jwtService, never()).isTokenValid(anyString());
    }

    @Test
    void refresh_invalidJwt_shouldReturn401WithoutDeletingToken() {
        User user = new User("test@example.com", "hash", "Test User");
        when(refreshTokenRepository.findByToken(REFRESH_TOKEN_VALUE)).thenReturn(Optional.of(validToken(user)));
        when(jwtService.isTokenValid(REFRESH_TOKEN_VALUE)).thenReturn(false);

        assertThatThrownBy(() -> userService.refresh(REFRESH_TOKEN_VALUE))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(refreshTokenRepository, never()).deleteByToken(anyString());
    }

    @Test
    void refresh_unknownToken_shouldReturn401() {
        when(refreshTokenRepository.findByToken("unknown-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.refresh("unknown-token"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(refreshTokenRepository, never()).deleteByToken(anyString());
    }
}

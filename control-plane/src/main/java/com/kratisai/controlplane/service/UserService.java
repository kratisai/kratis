package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.restdto.AuthTokensResponse;
import com.kratisai.controlplane.api.restdto.UpdateUserRequest;
import com.kratisai.controlplane.api.restdto.UserDto;
import com.kratisai.controlplane.model.RefreshToken;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.RefreshTokenRepository;
import com.kratisai.controlplane.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;
    private final long refreshTokenExpirationMs;
    private final TeamService teamService;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public UserService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager,
            TeamService teamService,
            @Value("${kratis.auth.jwt.refresh-expiration}") long refreshTokenExpirationMs) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.entityManager = entityManager;
        this.teamService = teamService;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public UserDto register(String email, String password, String displayName) {
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }

        User user = new User(email, passwordEncoder.encode(password), displayName);
        userRepository.save(user);

        // Create a default team for the new user
        teamService.createDefaultTeam(user.getId());

        return toDto(user);
    }

    @Transactional
    public AuthTokensResponse login(String email, String password) {
        User user = userRepository
                .findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        return createTokens(user);
    }

    @Transactional
    public AuthTokensResponse refresh(String refreshToken) {
        RefreshToken stored = refreshTokenRepository
                .findByToken(refreshToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        if (stored.isExpired()) {
            entityManager.detach(stored);
            requiresNewTransactionTemplate.executeWithoutResult(
                    status -> refreshTokenRepository.deleteByToken(refreshToken));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }

        if (!jwtService.isTokenValid(refreshToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        User user = stored.getUser();
        entityManager.detach(stored);
        if (refreshTokenRepository.deleteByToken(refreshToken) == 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }

        return createTokens(user);
    }

    @Transactional
    public UserDto updateProfile(UUID userId, UpdateUserRequest request) {
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.email() != null) {
            if (userRepository.existsByEmail(request.email())
                    && !user.getEmail().equals(request.email())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
            }
            user.setEmail(request.email());
        }
        userRepository.save(user);

        return toDto(user);
    }

    private AuthTokensResponse createTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtService.generateRefreshToken(user.getId(), user.getEmail());

        RefreshToken tokenEntity =
                new RefreshToken(refreshToken, user, Instant.now().plusMillis(refreshTokenExpirationMs));
        refreshTokenRepository.save(tokenEntity);

        return new AuthTokensResponse(
                accessToken, refreshToken, jwtService.getAccessTokenExpiration() / 1000, toDto(user));
    }

    private UserDto toDto(User user) {
        return new UserDto(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }
}

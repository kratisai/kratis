package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.api.restdto.*;
import com.kratisai.controlplane.config.SecurityUtil;
import com.kratisai.controlplane.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "User registration, login, and token management endpoints")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @Operation(
            summary = "Register a new user",
            description = "Creates a new user account with the provided email, password, and display name. "
                    + "Returns the created user information. Email must be unique.")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "User successfully registered",
                        content = @Content(schema = @Schema(implementation = UserDto.class))),
                @ApiResponse(
                        responseCode = "400",
                        description = "Invalid request body or validation errors",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        examples =
                                                @ExampleObject(
                                                        name = "validation-error",
                                                        summary = "Validation error response",
                                                        value =
                                                                "{\"message\": \"Validation failed\", \"errors\": {\"email\": \"Email must be valid\"}}"))),
                @ApiResponse(responseCode = "409", description = "Email already exists", content = @Content)
            })
    public ResponseEntity<UserDto> register(
            @Valid @RequestBody @Parameter(description = "User registration details", required = true)
                    RegisterUserRequest request) {
        UserDto user = userService.register(request.email(), request.password(), request.displayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    @PostMapping("/login")
    @Operation(
            summary = "Login with existing credentials",
            description = "Authenticates a user with email and password. Returns JWT access and refresh tokens.")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Login successful",
                        content = @Content(schema = @Schema(implementation = AuthTokensResponse.class))),
                @ApiResponse(responseCode = "401", description = "Invalid credentials", content = @Content)
            })
    public ResponseEntity<AuthTokensResponse> login(
            @Valid @RequestBody @Parameter(description = "Login credentials", required = true) LoginRequest request) {
        AuthTokensResponse response = userService.login(request.email(), request.password());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Refresh access token",
            description = "Uses a valid refresh token to obtain a new access token and refresh token pair.")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Tokens refreshed successfully",
                        content = @Content(schema = @Schema(implementation = AuthTokensResponse.class))),
                @ApiResponse(responseCode = "401", description = "Invalid or expired refresh token", content = @Content)
            })
    public ResponseEntity<AuthTokensResponse> refresh(
            @Valid @RequestBody @Parameter(description = "Refresh token request", required = true)
                    RefreshTokenRequest request) {
        AuthTokensResponse response = userService.refresh(request.refreshToken());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/me")
    @Operation(
            summary = "Update current user profile",
            description = "Updates the authenticated user's display name and/or email.")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Profile updated",
                        content = @Content(schema = @Schema(implementation = UserDto.class))),
                @ApiResponse(responseCode = "400", description = "Invalid request body", content = @Content),
                @ApiResponse(responseCode = "409", description = "Email already exists", content = @Content)
            })
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<UserDto> updateProfile(
            @Valid @RequestBody @Parameter(description = "Profile update details", required = true)
                    UpdateUserRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(userService.updateProfile(userId, request));
    }
}

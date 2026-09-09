package main

import (
	"os"
	"strings"
	"testing"
)

func TestParseConfig_AllFlags(t *testing.T) {
	cfg, err := ParseConfig([]string{
		"--mode", "daemon",
		"--token", "test-token",
		"--server-url", "ws://localhost:8080",
		"--container-id", "container-123",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg.Mode != "daemon" {
		t.Errorf("expected mode 'daemon', got '%s'", cfg.Mode)
	}
	if cfg.Token != "test-token" {
		t.Errorf("expected token 'test-token', got '%s'", cfg.Token)
	}
	if cfg.ServerURL != "ws://localhost:8080" {
		t.Errorf("expected server URL 'ws://localhost:8080', got '%s'", cfg.ServerURL)
	}
	if cfg.ContainerID != "container-123" {
		t.Errorf("expected container ID 'container-123', got '%s'", cfg.ContainerID)
	}
	if cfg.Workspace == "" {
		t.Error("expected non-empty workspace")
	}
}

func TestParseConfig_EnvFallback(t *testing.T) {
	// Set env vars
	if err := os.Setenv("KRATIS_TOKEN", "env-token"); err != nil {
		t.Fatalf("failed to set env: %v", err)
	}
	if err := os.Setenv("KRATIS_SERVER_URL", "ws://env-server:8080"); err != nil {
		t.Fatalf("failed to set env: %v", err)
	}
	if err := os.Setenv("KRATIS_CONTAINER_ID", "env-container"); err != nil {
		t.Fatalf("failed to set env: %v", err)
	}
	defer func() {
		_ = os.Unsetenv("KRATIS_TOKEN")
		_ = os.Unsetenv("KRATIS_SERVER_URL")
		_ = os.Unsetenv("KRATIS_CONTAINER_ID")
	}()

	cfg, err := ParseConfig([]string{})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg.Token != "env-token" {
		t.Errorf("expected token 'env-token', got '%s'", cfg.Token)
	}
	if cfg.ServerURL != "ws://env-server:8080" {
		t.Errorf("expected server URL 'ws://env-server:8080', got '%s'", cfg.ServerURL)
	}
	if cfg.ContainerID != "env-container" {
		t.Errorf("expected container ID 'env-container', got '%s'", cfg.ContainerID)
	}
}

func TestParseConfig_FlagOverridesEnv(t *testing.T) {
	if err := os.Setenv("KRATIS_TOKEN", "env-token"); err != nil {
		t.Fatalf("failed to set env: %v", err)
	}
	if err := os.Setenv("KRATIS_SERVER_URL", "ws://env-server:8080"); err != nil {
		t.Fatalf("failed to set env: %v", err)
	}
	defer func() {
		_ = os.Unsetenv("KRATIS_TOKEN")
		_ = os.Unsetenv("KRATIS_SERVER_URL")
	}()

	cfg, err := ParseConfig([]string{
		"--token", "flag-token",
		"--server-url", "ws://flag-server:8080",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg.Token != "flag-token" {
		t.Errorf("expected flag token 'flag-token', got '%s'", cfg.Token)
	}
	if cfg.ServerURL != "ws://flag-server:8080" {
		t.Errorf("expected flag server URL 'ws://flag-server:8080', got '%s'", cfg.ServerURL)
	}
}

func TestParseConfig_MissingToken(t *testing.T) {
	// Ensure env is clear
	_ = os.Unsetenv("KRATIS_TOKEN")
	_ = os.Unsetenv("KRATIS_SERVER_URL")

	_, err := ParseConfig([]string{
		"--server-url", "ws://localhost:8080",
	})
	if err == nil {
		t.Fatal("expected error for missing token")
	}
	if !strings.Contains(err.Error(), "token is required") {
		t.Errorf("unexpected error message: %v", err)
	}
}

func TestParseConfig_MissingServerURL(t *testing.T) {
	_ = os.Unsetenv("KRATIS_TOKEN")
	_ = os.Unsetenv("KRATIS_SERVER_URL")

	_, err := ParseConfig([]string{
		"--token", "test-token",
	})
	if err == nil {
		t.Fatal("expected error for missing server-url")
	}
	if !strings.Contains(err.Error(), "server-url is required") {
		t.Errorf("unexpected error message: %v", err)
	}
}

func TestParseConfig_DefaultMode(t *testing.T) {
	cfg, err := ParseConfig([]string{
		"--token", "test-token",
		"--server-url", "ws://localhost:8080",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg.Mode != "sidecar" {
		t.Errorf("expected default mode 'sidecar', got '%s'", cfg.Mode)
	}
}

func TestParseConfig_InvalidFlag(t *testing.T) {
	_, err := ParseConfig([]string{"--unknown-flag"})
	if err == nil {
		t.Fatal("expected error for unknown flag")
	}
}

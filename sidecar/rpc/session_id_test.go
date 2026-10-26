package rpc

import (
	"path/filepath"
	"testing"
)

func TestPersistAndReadSessionID(t *testing.T) {
	dir := t.TempDir()
	c := NewClient("ws://example", "token", dir)
	c.stateDir = dir

	if got := c.readPersistedSessionID(); got != "" {
		t.Fatalf("expected empty session id before any persist, got %q", got)
	}

	if want := filepath.Join(dir, "acp_session_id"); c.acpSessionIDPath() != want {
		t.Errorf("acpSessionIDPath() = %q, want %q", c.acpSessionIDPath(), want)
	}

	c.persistSessionID("session-abc-123")
	if got := c.readPersistedSessionID(); got != "session-abc-123" {
		t.Errorf("readPersistedSessionID() = %q, want %q", got, "session-abc-123")
	}
}

func TestPersistSessionIDIgnoresEmpty(t *testing.T) {
	dir := t.TempDir()
	c := NewClient("ws://example", "token", dir)
	c.stateDir = dir

	c.persistSessionID("")
	if got := c.readPersistedSessionID(); got != "" {
		t.Errorf("expected no session id written for empty input, got %q", got)
	}
}

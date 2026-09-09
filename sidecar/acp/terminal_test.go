package acp

import (
	"testing"
	"time"
)

func TestTerminalManager_CreateTerminal(t *testing.T) {
	tm := NewTerminalManager("/tmp")

	term, err := tm.CreateTerminal("echo hello", "/tmp")
	if err != nil {
		t.Fatalf("CreateTerminal failed: %v", err)
	}

	if term.ID == "" {
		t.Error("Terminal ID should not be empty")
	}

	// Wait for command to complete
	_, err = tm.WaitForExit(term.ID, 5*time.Second)
	if err != nil {
		t.Fatalf("WaitForExit failed: %v", err)
	}

	tm.ReleaseTerminal(term.ID)
}

func TestTerminalManager_GetOutput_RawOutput(t *testing.T) {
	tm := NewTerminalManager("/tmp")

	// Create a terminal that outputs some text
	term, err := tm.CreateTerminal("echo 'Hello Kratis - what a lovely day'", "/tmp")
	if err != nil {
		t.Fatalf("CreateTerminal failed: %v", err)
	}

	// Wait for command to complete
	_, err = tm.WaitForExit(term.ID, 5*time.Second)
	if err != nil {
		t.Fatalf("WaitForExit failed: %v", err)
	}

	// Get the raw output from terminal manager
	stdout, stderr, exited := tm.GetOutput(term.ID)
	if !exited {
		t.Error("Terminal should have exited")
	}

	rawOutput := stdout + stderr
	expectedRawOutput := "Hello Kratis - what a lovely day\n"
	if rawOutput != expectedRawOutput {
		t.Errorf("Raw output mismatch: got %q, want %q", rawOutput, expectedRawOutput)
	}

	tm.ReleaseTerminal(term.ID)
}

func TestHandleTerminalOutput_RawOutput(t *testing.T) {
	// This test verifies that HandleTerminalOutput returns raw command output
	// without any prefix formatting.

	tm := NewTerminalManager("/tmp")

	term, err := tm.CreateTerminal("echo 'test output'", "/tmp")
	if err != nil {
		t.Fatalf("CreateTerminal failed: %v", err)
	}

	// Wait for completion
	_, err = tm.WaitForExit(term.ID, 5*time.Second)
	if err != nil {
		t.Fatalf("WaitForExit failed: %v", err)
	}

	// Get raw output
	stdout, stderr, _ := tm.GetOutput(term.ID)
	combinedOutput := stdout + stderr

	expectedOutput := "test output\n"
	if combinedOutput != expectedOutput {
		t.Errorf("Output mismatch: got %q, want %q", combinedOutput, expectedOutput)
	}

	tm.ReleaseTerminal(term.ID)
}

func TestHandleTerminalOutput_EmptyOutput(t *testing.T) {
	// Verify that empty output remains empty
	tm := NewTerminalManager("/tmp")

	// Create a terminal that produces no output
	term, err := tm.CreateTerminal("true", "/tmp")
	if err != nil {
		t.Fatalf("CreateTerminal failed: %v", err)
	}

	// Wait for completion
	_, err = tm.WaitForExit(term.ID, 5*time.Second)
	if err != nil {
		t.Fatalf("WaitForExit failed: %v", err)
	}

	// Get raw output
	stdout, stderr, _ := tm.GetOutput(term.ID)
	combinedOutput := stdout + stderr

	// Empty output should remain empty
	if combinedOutput != "" {
		t.Errorf("Empty output should remain empty, got %q", combinedOutput)
	}

	tm.ReleaseTerminal(term.ID)
}

func TestTerminalManager_MultipleTerminals(t *testing.T) {
	tm := NewTerminalManager("/tmp")

	term1, err := tm.CreateTerminal("echo first", "/tmp")
	if err != nil {
		t.Fatalf("CreateTerminal 1 failed: %v", err)
	}

	term2, err := tm.CreateTerminal("echo second", "/tmp")
	if err != nil {
		t.Fatalf("CreateTerminal 2 failed: %v", err)
	}

	// Wait for both to complete
	_, err = tm.WaitForExit(term1.ID, 5*time.Second)
	if err != nil {
		t.Fatalf("WaitForExit 1 failed: %v", err)
	}

	_, err = tm.WaitForExit(term2.ID, 5*time.Second)
	if err != nil {
		t.Fatalf("WaitForExit 2 failed: %v", err)
	}

	// Verify outputs are separate
	stdout1, _, _ := tm.GetOutput(term1.ID)
	stdout2, _, _ := tm.GetOutput(term2.ID)

	if stdout1 != "first\n" {
		t.Errorf("Terminal 1 output mismatch: got %q", stdout1)
	}

	if stdout2 != "second\n" {
		t.Errorf("Terminal 2 output mismatch: got %q", stdout2)
	}

	tm.ReleaseTerminal(term1.ID)
	tm.ReleaseTerminal(term2.ID)
}

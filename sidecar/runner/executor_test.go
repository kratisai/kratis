package runner

import (
	"context"
	"testing"
)

func TestExecutor_Success(t *testing.T) {
	e := NewExecutor()
	var lines []OutputLine
	callback := func(ol OutputLine) {
		lines = append(lines, ol)
	}

	exitCode, err := e.Execute(context.Background(), "echo 'hello world'", callback)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if exitCode != 0 {
		t.Errorf("expected exit code 0, got %d", exitCode)
	}

	if len(lines) != 1 || lines[0].Line != "hello world" || lines[0].Stream != "stdout" {
		t.Errorf("unexpected output lines: %v", lines)
	}
}

func TestExecutor_Failure(t *testing.T) {
	e := NewExecutor()
	exitCode, err := e.Execute(context.Background(), "exit 42", nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if exitCode != 42 {
		t.Errorf("expected exit code 42, got %d", exitCode)
	}
}

func TestExecutor_ShellCommandNotFound(t *testing.T) {
	e := NewExecutor()
	exitCode, err := e.Execute(context.Background(), "/nonexistent/binary/that/does/not/exist", nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	// sh -c returns 127 for command not found
	if exitCode != 127 {
		t.Errorf("expected exit code 127 for command not found, got %d", exitCode)
	}
}

func TestExecutor_ContextCancelled(t *testing.T) {
	e := NewExecutor()
	ctx, cancel := context.WithCancel(context.Background())
	cancel() // Cancel immediately
	exitCode, err := e.Execute(ctx, "sleep 60", nil)
	// The context is already cancelled, so the command should fail
	if err != nil {
		// Start may fail with context error
		return
	}
	// Or it may return a non-zero exit code
	if exitCode == 0 {
		t.Error("expected non-zero exit code for cancelled context")
	}
}

func TestExecutor_DirAndEnv(t *testing.T) {
	e := NewExecutor()
	e.Dir = "/"
	e.Env = []string{"FOO=bar"}

	var lines []OutputLine
	callback := func(ol OutputLine) {
		lines = append(lines, ol)
	}

	exitCode, err := e.Execute(context.Background(), "echo $FOO", callback)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if exitCode != 0 {
		t.Errorf("expected exit status 0, got %d", exitCode)
	}
	if len(lines) != 1 || lines[0].Line != "bar" {
		t.Errorf("expected output to have 'bar', got %v", lines)
	}
}

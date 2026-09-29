package runner

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type collectingSink struct {
	mu    sync.Mutex
	lines []string
}

func (s *collectingSink) SendOutput(line string, stream string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.lines = append(s.lines, stream+"|"+line)
}

func (s *collectingSink) snapshot() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.lines...)
}

func (s *collectingSink) waitFor(t *testing.T, want int, d time.Duration) []string {
	t.Helper()
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if len(s.snapshot()) >= want {
			return s.snapshot()
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %d lines, got %d: %v", want, len(s.snapshot()), s.snapshot())
	return nil
}

func newTestTailer(sink LogSink, paths []string) *AgentLogTailer {
	t := &AgentLogTailer{
		sink:     sink,
		paths:    paths,
		interval: 10 * time.Millisecond,
		stop:     make(chan struct{}),
		done:     make(chan struct{}),
	}
	t.start()
	return t
}

func TestSplitLines(t *testing.T) {
	lines, partial := splitLines([]byte("one\ntwo\r\nthr"))
	if len(lines) != 2 || lines[0] != "one" || lines[1] != "two" {
		t.Fatalf("lines = %v", lines)
	}
	if string(partial) != "thr" {
		t.Fatalf("partial = %q", partial)
	}
	lines, partial = splitLines(append(partial, []byte("ee\n")...))
	if len(lines) != 1 || lines[0] != "three" || len(partial) != 0 {
		t.Fatalf("lines = %v partial = %q", lines, partial)
	}
}

func TestAgentLogTailer_RawLineLifecycle(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "agent-debug.log")

	// Pre-existing content must not be replayed: the tailer starts at EOF.
	if err := os.WriteFile(logPath, []byte("before session start\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	sink := &collectingSink{}
	tailer := newTestTailer(sink, []string{logPath})

	// Let the tailer snapshot the pre-existing size (start-at-EOF semantics)
	// before appending session output.
	time.Sleep(30 * time.Millisecond)

	f, err := os.OpenFile(logPath, os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = f.Close() }()
	_, _ = fmt.Fprintln(f, "debug noisy internals")
	_, _ = fmt.Fprintln(f, "ERROR: turn aborted\r")
	_, _ = fmt.Fprintln(f, "retrying in 2s")

	sink.waitFor(t, 3, 3*time.Second)
	tailer.Stop()

	// Final poll on Stop must not duplicate or drop entries.
	lines := sink.snapshot()
	if len(lines) != 3 {
		t.Fatalf("forwarded %d lines: %v", len(lines), lines)
	}
	if lines[0] != AgentStream+"|debug noisy internals" {
		t.Errorf("line 0 = %q", lines[0])
	}
	if lines[1] != AgentStream+"|ERROR: turn aborted" {
		t.Errorf("line 1 = %q (CR should be trimmed)", lines[1])
	}
	if !strings.Contains(lines[2], "retrying in 2s") {
		t.Errorf("line 2 = %q", lines[2])
	}
	for _, l := range lines {
		if strings.Contains(l, "before session start") {
			t.Errorf("pre-existing content replayed: %q", l)
		}
	}
}

func TestAgentLogTailer_WaitsForLateFile(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "later.log")

	sink := &collectingSink{}
	tailer := newTestTailer(sink, []string{logPath})
	defer tailer.Stop()

	time.Sleep(30 * time.Millisecond)
	if err := os.WriteFile(logPath, []byte("late but captured\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	lines := sink.waitFor(t, 1, 3*time.Second)
	if !strings.Contains(lines[0], "late but captured") {
		t.Fatalf("lines = %v", lines)
	}
}

func TestAgentLogTailer_TailsEveryConfiguredFile(t *testing.T) {
	dir := t.TempDir()
	first := filepath.Join(dir, "first.log")
	second := filepath.Join(dir, "second.log")

	sink := &collectingSink{}
	tailer := newTestTailer(sink, []string{first, second})
	defer tailer.Stop()

	time.Sleep(30 * time.Millisecond)
	if err := os.WriteFile(first, []byte("from first\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(second, []byte("from second\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	// No attribution: lines from both files are forwarded identically.
	lines := sink.waitFor(t, 2, 3*time.Second)
	var gotFirst, gotSecond bool
	for _, l := range lines {
		switch l {
		case AgentStream + "|from first":
			gotFirst = true
		case AgentStream + "|from second":
			gotSecond = true
		}
	}
	if !gotFirst || !gotSecond {
		t.Fatalf("missing file content, lines = %v", lines)
	}
}

func TestAgentLogTailer_ForwardCap(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "flood.log")

	sink := &collectingSink{}
	tailer := newTestTailer(sink, []string{logPath})
	defer tailer.Stop()

	// Let the tailer take its initial snapshot before the file exists.
	time.Sleep(30 * time.Millisecond)
	f, err := os.Create(logPath)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < agentLogForwardCap+50; i++ {
		_, _ = fmt.Fprintf(f, "flood %d\n", i)
	}
	_ = f.Close()

	sink.waitFor(t, agentLogForwardCap+1, 5*time.Second)
	lines := sink.snapshot()
	suppressed := 0
	for _, l := range lines {
		if strings.Contains(l, "further agent log entries suppressed") {
			suppressed++
		}
	}
	if suppressed != 1 {
		t.Fatalf("expected exactly one suppression notice, got %d in %d lines", suppressed, len(lines))
	}
	if len(lines) != agentLogForwardCap+1 {
		t.Fatalf("expected %d lines, got %d", agentLogForwardCap+1, len(lines))
	}
}

func TestAgentLogTailer_TruncatedFileRestartsFromStart(t *testing.T) {
	dir := t.TempDir()
	logPath := filepath.Join(dir, "recycled.log")

	sink := &collectingSink{}
	tailer := newTestTailer(sink, []string{logPath})
	defer tailer.Stop()

	time.Sleep(30 * time.Millisecond)
	if err := os.WriteFile(logPath, []byte("first session line one\nfirst session line two\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	sink.waitFor(t, 2, 3*time.Second)

	// Recreated (truncated) file: tailing restarts from the beginning instead
	// of sitting at a now-invalid offset.
	if err := os.WriteFile(logPath, []byte("second session line\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	lines := sink.waitFor(t, 3, 3*time.Second)
	if lines[2] != AgentStream+"|second session line" {
		t.Fatalf("line 2 = %q", lines[2])
	}
}

func TestAgentLogTailer_StopOnNilAndEmpty(t *testing.T) {
	var nilTailer *AgentLogTailer
	nilTailer.Stop() // must not panic

	if StartAgentLogTailing(&collectingSink{}, nil) != nil {
		t.Fatal("expected nil tailer for nil paths")
	}
	if StartAgentLogTailing(&collectingSink{}, []string{}) != nil {
		t.Fatal("expected nil tailer for empty paths")
	}
}

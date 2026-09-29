package runner

import (
	"bytes"
	"fmt"
	"os"
	"strings"
	"sync"
	"time"
)

const (
	agentLogPollInterval   = 500 * time.Millisecond
	agentLogMaxReadPerPoll = 256 * 1024
	agentLogForwardCap     = 200
)

// AgentStream is the env.output stream value the tailer forwards on. The rpc
// package mirrors this value as StreamAgent.
const AgentStream = "agent"

// AgentLogTailer tails the diagnostic log files an agent harness writes inside
// the sandbox and forwards complete raw lines through SendOutput on the agent
// stream. No format parsing or per-file attribution is applied; the forward
// cap is the only noise control.
type AgentLogTailer struct {
	sink     LogSink
	paths    []string
	interval time.Duration
	stop     chan struct{}
	done     chan struct{}
	once     sync.Once
}

// LogSink is the slice of EventSink the tailer needs.
type LogSink interface {
	SendOutput(line string, stream string)
}

// StartAgentLogTailing begins tailing the declared files. Files may not exist
// yet; each tailer picks them up when they appear and starts from the current
// end-of-file so a relaunch does not replay the previous session's entries.
func StartAgentLogTailing(sink LogSink, paths []string) *AgentLogTailer {
	if len(paths) == 0 {
		return nil
	}
	t := &AgentLogTailer{
		sink:     sink,
		paths:    paths,
		interval: agentLogPollInterval,
		stop:     make(chan struct{}),
		done:     make(chan struct{}),
	}
	t.start()
	return t
}

func (t *AgentLogTailer) start() {
	var wg sync.WaitGroup
	for _, p := range t.paths {
		wg.Add(1)
		go func(p string) {
			defer wg.Done()
			t.tail(p)
		}(p)
	}
	go func() {
		wg.Wait()
		close(t.done)
	}()
}

// Stop ends tailing after a final read pass so entries written moments before
// the agent exited are still forwarded. Safe to call on a nil tailer.
func (t *AgentLogTailer) Stop() {
	if t == nil {
		return
	}
	t.once.Do(func() { close(t.stop) })
	<-t.done
}

func (t *AgentLogTailer) tail(path string) {
	var offset int64
	if st, err := os.Stat(path); err == nil {
		offset = st.Size()
	}
	partial := make([]byte, 0, 4096)
	state := &tailState{}

	poll := func() {
		st, err := os.Stat(path)
		if err != nil || st.Size() == offset {
			return
		}
		if st.Size() < offset {
			// Recreated or truncated: start over and drop any partial state.
			offset = 0
			partial = partial[:0]
		}
		fh, err := os.Open(path) //nolint:gosec // G304: path is the harness-declared agent log file
		if err != nil {
			return
		}
		defer func() { _ = fh.Close() }()
		if _, err := fh.Seek(offset, 0); err != nil {
			return
		}
		buf := make([]byte, agentLogMaxReadPerPoll)
		n, _ := fh.Read(buf) // short reads and EOF are fine; the next poll continues
		if n == 0 {
			return
		}
		offset += int64(n)
		partial = append(partial, buf[:n]...)
		var lines []string
		lines, partial = splitLines(partial)
		for _, line := range lines {
			t.forward(line, state, path)
		}
	}

	ticker := time.NewTicker(t.interval)
	defer ticker.Stop()
	for {
		select {
		case <-t.stop:
			poll()
			return
		case <-ticker.C:
			poll()
		}
	}
}

// tailState is per-tailer so the forward cap is shared across files and the
// suppression notice fires once regardless of how many files are configured.
type tailState struct {
	forwarded int
	capped    bool
}

func (t *AgentLogTailer) forward(line string, state *tailState, path string) {
	if state.forwarded >= agentLogForwardCap {
		if !state.capped {
			state.capped = true
			t.sink.SendOutput(fmt.Sprintf("further agent log entries suppressed (cap %d); full logs at %s in the sandbox", agentLogForwardCap, path), AgentStream)
		}
		return
	}
	state.forwarded++
	t.sink.SendOutput(line, AgentStream)
}

// splitLines cuts the buffer into complete lines, returning them and the
// trailing partial line.
func splitLines(buf []byte) ([]string, []byte) {
	var lines []string
	for {
		i := bytes.IndexByte(buf, '\n')
		if i < 0 {
			break
		}
		lines = append(lines, strings.TrimRight(string(buf[:i]), "\r"))
		buf = buf[i+1:]
	}
	out := make([]byte, len(buf))
	copy(out, buf)
	return lines, out
}

package rpc

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func newLogClient(t *testing.T) *Client {
	t.Helper()
	return &Client{terminalLogFile: filepath.Join(t.TempDir(), "logs", "terminal.log")}
}

func TestAppendTerminalLogPersistsStdoutAndStderrOnly(t *testing.T) {
	c := newLogClient(t)
	c.appendTerminalLog("stdout", "one")
	c.appendTerminalLog("stderr", "two")
	c.appendTerminalLog("agent", "ignored")

	data, err := os.ReadFile(c.terminalLogFile)
	if err != nil {
		t.Fatalf("read log: %v", err)
	}
	if string(data) != "one\ntwo\n" {
		t.Errorf("unexpected log content %q", data)
	}
}

func TestReadTerminalTail(t *testing.T) {
	c := newLogClient(t)
	empty, err := c.readTerminalTail(5)
	if err != nil || len(empty) != 0 {
		t.Fatalf("missing file should be empty log, got %v, %v", empty, err)
	}
	for i := 1; i <= 10; i++ {
		c.appendTerminalLog("stdout", fmt.Sprintf("line %d", i))
	}
	lines, err := c.readTerminalTail(3)
	if err != nil {
		t.Fatalf("tail: %v", err)
	}
	if strings.Join(lines, "|") != "line 8|line 9|line 10" {
		t.Errorf("unexpected tail %v", lines)
	}
	all, _ := c.readTerminalTail(100)
	if len(all) != 10 {
		t.Errorf("expected 10 lines, got %d", len(all))
	}
}

func TestCompactTerminalLogKeepsNewestLines(t *testing.T) {
	c := newLogClient(t)
	c.appendTerminalLog("stdout", "old")
	big := strings.Repeat("x", 1024*1024)
	for i := 0; i < 17; i++ {
		c.appendTerminalLog("stdout", big)
	}
	c.appendTerminalLog("stdout", "newest")
	info, err := os.Stat(c.terminalLogFile)
	if err != nil {
		t.Fatal(err)
	}
	if info.Size() > maxTerminalLogBytes {
		t.Errorf("log not compacted: %d bytes", info.Size())
	}
	lines, _ := c.readTerminalTail(1)
	if len(lines) != 1 || lines[0] != "newest" {
		t.Errorf("newest line lost: %v", lines)
	}
}

func TestExecuteGetLogsRespondsWithTail(t *testing.T) {
	client, received := connectedGitClientWith(t, t.TempDir())
	client.terminalLogFile = filepath.Join(t.TempDir(), "terminal.log")
	client.appendTerminalLog("stdout", "a")
	client.appendTerminalLog("stderr", "b")
	client.appendTerminalLog("stdout", "c")

	client.ExecuteGetLogs(GetLogsParams{TailLines: 2}, "req-logs")

	var envelope struct {
		Result GetLogsResult `json:"result"`
	}
	if err := json.Unmarshal(captureNextResponse(t, received), &envelope); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if strings.Join(envelope.Result.Lines, ",") != "b,c" {
		t.Errorf("unexpected lines %v", envelope.Result.Lines)
	}
}

func TestExecuteGetLogsRejectsNonPositiveTail(t *testing.T) {
	client, received := connectedGitClientWith(t, t.TempDir())
	client.ExecuteGetLogs(GetLogsParams{TailLines: 0}, "req-bad")

	var envelope struct {
		Error *JsonRpcError `json:"error"`
	}
	if err := json.Unmarshal(captureNextResponse(t, received), &envelope); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if envelope.Error == nil || envelope.Error.Code != -32602 {
		t.Errorf("expected -32602, got %+v", envelope.Error)
	}
}

func TestExecuteExecAppendsToTerminalLog(t *testing.T) {
	client, _ := connectedGitClientWith(t, t.TempDir())
	client.terminalLogFile = filepath.Join(t.TempDir(), "terminal.log")
	client.ExecuteExec(ExecParams{Command: "echo hello; echo oops 1>&2"}, "req-exec")

	lines, err := client.readTerminalTail(10)
	if err != nil {
		t.Fatal(err)
	}
	joined := strings.Join(lines, "|")
	if !strings.Contains(joined, "hello") || !strings.Contains(joined, "oops") {
		t.Errorf("exec output missing from terminal log: %v", lines)
	}
}

func TestSendOutputAppendsToTerminalLog(t *testing.T) {
	client, _ := connectedGitClientWith(t, t.TempDir())
	client.terminalLogFile = filepath.Join(t.TempDir(), "terminal.log")
	client.SendOutput("from acp", "stdout")

	lines, _ := client.readTerminalTail(5)
	if len(lines) != 1 || lines[0] != "from acp" {
		t.Errorf("unexpected lines %v", lines)
	}
}

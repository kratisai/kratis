package rpc

import (
	"bytes"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
)

const (
	// maxTailLines bounds one env.get_logs reply regardless of the request.
	maxTailLines = 5000
	// maxTailBytes bounds the bytes scanned and returned for one reply so the
	// response frame stays far below maxOutboundFrameBytes.
	maxTailBytes = 2 * 1024 * 1024
	// maxTerminalLogBytes triggers compaction; the newest half is kept.
	maxTerminalLogBytes = 16 * 1024 * 1024
)

// defaultTerminalLogFile is a variable so tests can redirect it away from a
// live sandbox's /kratis/logs.
var defaultTerminalLogFile = "/kratis/logs/terminal.log"

// appendTerminalLog persists one console line. Failures are logged and
// swallowed: live streaming must never depend on the catch-up log.
func (c *Client) appendTerminalLog(stream, line string) {
	if stream != string(StreamStdout) && stream != string(StreamStderr) {
		return
	}
	path := c.terminalLogPath()
	line = strings.ReplaceAll(line, "\n", " ")

	c.terminalLogMu.Lock()
	defer c.terminalLogMu.Unlock()

	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		log.Printf("terminal log: mkdir failed: %v", err)
		return
	}
	if info, err := os.Stat(path); err == nil && info.Size() > maxTerminalLogBytes {
		compactTerminalLog(path)
	}
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600) //nolint:gosec
	if err != nil {
		log.Printf("terminal log: open failed: %v", err)
		return
	}
	if _, err := f.WriteString(line + "\n"); err != nil {
		log.Printf("terminal log: write failed: %v", err)
	}
	if err := f.Close(); err != nil {
		log.Printf("terminal log: close failed: %v", err)
	}
}

func (c *Client) terminalLogPath() string {
	if c.terminalLogFile != "" {
		return c.terminalLogFile
	}
	return defaultTerminalLogFile
}

// compactTerminalLog keeps roughly the newest half of the file, starting at a
// line boundary. The caller holds terminalLogMu.
func compactTerminalLog(path string) {
	data, err := readTailBytes(path, maxTerminalLogBytes/2)
	if err != nil {
		return
	}
	if i := bytes.IndexByte(data, '\n'); i >= 0 {
		data = data[i+1:]
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		log.Printf("terminal log: compaction failed: %v", err)
	}
}

// readTailBytes returns up to limit trailing bytes of the file.
func readTailBytes(path string, limit int64) ([]byte, error) {
	f, err := os.Open(path) //nolint:gosec
	if err != nil {
		return nil, err
	}
	defer func() { _ = f.Close() }()
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	start := info.Size() - limit
	if start < 0 {
		start = 0
	}
	if _, err := f.Seek(start, io.SeekStart); err != nil {
		return nil, err
	}
	return io.ReadAll(f)
}

// readTerminalTail returns the last n lines. A missing file is an empty log.
func (c *Client) readTerminalTail(n int) ([]string, error) {
	c.terminalLogMu.Lock()
	defer c.terminalLogMu.Unlock()

	data, err := readTailBytes(c.terminalLogPath(), maxTailBytes)
	if os.IsNotExist(err) {
		return []string{}, nil
	}
	if err != nil {
		return nil, err
	}
	text := strings.TrimSuffix(string(data), "\n")
	if text == "" {
		return []string{}, nil
	}
	lines := strings.Split(text, "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return lines, nil
}

// ExecuteGetLogs replies with the trailing lines of the terminal log.
func (c *Client) ExecuteGetLogs(params GetLogsParams, reqID interface{}) {
	if params.TailLines < 1 {
		c.sendErrorResponse(reqID, -32602, "Invalid parameters", "tailLines must be at least 1")
		return
	}
	n := params.TailLines
	if n > maxTailLines {
		n = maxTailLines
	}
	lines, err := c.readTerminalTail(n)
	if err != nil {
		c.sendErrorResponse(reqID, -32000, "Terminal log unreadable", err.Error())
		return
	}
	c.sendSuccessResponse(reqID, GetLogsResult{Lines: lines})
}

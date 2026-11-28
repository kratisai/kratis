package rpc

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// publishFrame captures one inbound JSON-RPC request so tests can assert method + params.
type publishFrame struct {
	Method string
	Params json.RawMessage
	ID     interface{}
}

// frameCollector is a mutex-guarded list of inbound JSON-RPC requests.
type frameCollector struct {
	mu     sync.Mutex
	frames []publishFrame
}

func (f *frameCollector) add(frame publishFrame) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.frames = append(f.frames, frame)
}

func (f *frameCollector) snapshot() []publishFrame {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]publishFrame(nil), f.frames...)
}

// startPublishTestServer answers env.preview and env.attachment and records every request it
// receives. Responses carry a control-plane-origin URL so the poller's result logging path
// is exercised end to end.
func startPublishTestServer(t *testing.T) (*httptest.Server, *frameCollector) {
	t.Helper()
	frames := &frameCollector{}
	srv := newTestServer(t, func(conn *websocket.Conn) {
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil || req.ID == nil {
				continue
			}
			var rawParams json.RawMessage
			if req.Params != nil {
				if b, err := json.Marshal(req.Params); err == nil {
					rawParams = b
				}
			}
			frames.add(publishFrame{Method: req.Method, Params: rawParams, ID: req.ID})
			var result json.RawMessage
			switch req.Method {
			case "env.preview":
				result = json.RawMessage(`{"type":"env_preview","status":"published","previewUrl":"http://control-plane/preview/env-1/"}`)
			case "env.attachment":
				result = json.RawMessage(`{"type":"env_attachment","status":"published","attachmentId":"a1","viewUrl":"http://control-plane/attachments/a1"}`)
			default:
				continue
			}
			_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: result, ID: req.ID})
		}
	})
	return srv, frames
}

// startPublishClient connects a client with a running readLoop so JSON-RPC responses are
// delivered to pending waiters; without it sendRequest blocks until its timeout.
func startPublishClient(t *testing.T, srv *httptest.Server) *Client {
	t.Helper()
	c := connectClient(t, wsURL(srv), "tok")
	setTestClientTimeouts(c)
	c.mu.Lock()
	c.currentExecutionID = "exec-publish-test"
	c.mu.Unlock()
	errChan := make(chan error, 1)
	go c.readLoop(errChan)
	return c
}

// TestPublishSpoolDirLivesInWorkspace proves the spool sits at .kratis/publish inside the
// workspace: harnesses restricted to the workspace can write it, and .kratis keeps it out of
// the diff.
func TestPublishSpoolDirLivesInWorkspace(t *testing.T) {
	workspace := t.TempDir()
	c := newTestClient(t, workspace)

	want := filepath.Join(workspace, ".kratis", "publish")
	if got := c.publishSpoolDir(); got != want {
		t.Fatalf("publishSpoolDir() = %q, want %q", got, want)
	}
}

func TestPublishPoller_PreviewManifestSendsPreviewRequest(t *testing.T) {
	srv, frames := startPublishTestServer(t)
	c := startPublishClient(t, srv)

	spool := c.publishSpoolDir()
	if err := os.MkdirAll(spool, 0o750); err != nil {
		t.Fatalf("mkdir spool: %v", err)
	}
	manifest := `{"port":3000,"label":"Web app","path":"/health"}`
	if err := os.WriteFile(filepath.Join(spool, "preview-1.json"), []byte(manifest), 0o600); err != nil {
		t.Fatalf("write manifest: %v", err)
	}

	poller := c.StartPublishPolling(nil)
	if poller == nil {
		t.Fatal("expected a poller")
	}
	defer poller.Stop()

	waitForFrames(t, frames, 1, "env.preview")

	snapshot := frames.snapshot()
	var params PreviewParams
	if err := json.Unmarshal(snapshot[0].Params, &params); err != nil {
		t.Fatalf("decode preview params: %v", err)
	}
	if params.Port != 3000 || params.Label != "Web app" || params.Path != "/health" {
		t.Errorf("preview params mismatch: %+v", params)
	}
	waitForFileAbsent(t, filepath.Join(spool, "preview-1.json"))
	waitForFilePresent(t, filepath.Join(spool, "done", "preview-1.json"))
}

func TestPublishPoller_AttachmentManifestSendsAttachmentRequest(t *testing.T) {
	srv, frames := startPublishTestServer(t)
	c := startPublishClient(t, srv)

	spool := c.publishSpoolDir()
	if err := os.MkdirAll(spool, 0o750); err != nil {
		t.Fatalf("mkdir spool: %v", err)
	}
	payload := []byte("# Report\n\nDone.")
	payloadPath := filepath.Join(spool, "attachment-report.md")
	if err := os.WriteFile(payloadPath, payload, 0o600); err != nil {
		t.Fatalf("write payload: %v", err)
	}
	meta := `{"attachmentId":"report-1","kind":"markdown","title":"Run report","contentType":"text/markdown"}`
	if err := os.WriteFile(filepath.Join(spool, "attachment-report.md.meta.json"), []byte(meta), 0o600); err != nil {
		t.Fatalf("write meta: %v", err)
	}

	poller := c.StartPublishPolling(nil)
	if poller == nil {
		t.Fatal("expected a poller")
	}
	defer poller.Stop()

	waitForFrames(t, frames, 1, "env.attachment")

	snapshot := frames.snapshot()
	var params AttachmentParams
	if err := json.Unmarshal(snapshot[0].Params, &params); err != nil {
		t.Fatalf("decode attachment params: %v", err)
	}
	if params.AttachmentID != "report-1" || params.Kind != AttachmentKindMarkdown {
		t.Errorf("attachment params mismatch: %+v", params)
	}
	if params.ContentType != "text/markdown" {
		t.Errorf("contentType = %q", params.ContentType)
	}
	if !strings.Contains(params.ContentBase64, "IyBSZXBvcnQ") {
		prefix := params.ContentBase64
		if len(prefix) > 24 {
			prefix = prefix[:24]
		}
		t.Errorf("expected base64 of markdown payload, got %q", prefix)
	}
	waitForFileAbsent(t, payloadPath)
}

func TestPublishPoller_JsonPayloadIsNotTreatedAsManifest(t *testing.T) {
	srv, frames := startPublishTestServer(t)
	c := startPublishClient(t, srv)

	spool := c.publishSpoolDir()
	if err := os.MkdirAll(spool, 0o750); err != nil {
		t.Fatalf("mkdir spool: %v", err)
	}
	// The payload itself ends in .json. Only the .meta.json is a manifest; the payload must
	// survive drain() so the attachment can be sent.
	payload := []byte(`{"report":"done"}`)
	payloadPath := filepath.Join(spool, "attachment-data.json")
	if err := os.WriteFile(payloadPath, payload, 0o600); err != nil {
		t.Fatalf("write payload: %v", err)
	}
	meta := `{"attachmentId":"data-1","kind":"markdown","title":"Data","contentType":"text/markdown"}`
	if err := os.WriteFile(filepath.Join(spool, "attachment-data.json.meta.json"), []byte(meta), 0o600); err != nil {
		t.Fatalf("write meta: %v", err)
	}

	poller := c.StartPublishPolling(nil)
	if poller == nil {
		t.Fatal("expected a poller")
	}
	defer poller.Stop()

	waitForFrames(t, frames, 1, "env.attachment")

	snapshot := frames.snapshot()
	var params AttachmentParams
	if err := json.Unmarshal(snapshot[0].Params, &params); err != nil {
		t.Fatalf("decode attachment params: %v", err)
	}
	if params.AttachmentID != "data-1" {
		t.Errorf("attachmentId = %q, want data-1", params.AttachmentID)
	}
	if !strings.Contains(params.ContentBase64, "eyJyZXBvcnQiOiJkb25lIn0") {
		t.Errorf("expected base64 of json payload, got %q", params.ContentBase64)
	}
}

func TestPublishPoller_RejectsOversizedAndUnknownKind(t *testing.T) {
	srv, frames := startPublishTestServer(t)
	c := startPublishClient(t, srv)

	spool := c.publishSpoolDir()
	if err := os.MkdirAll(spool, 0o750); err != nil {
		t.Fatalf("mkdir spool: %v", err)
	}
	// Oversized payload: content longer than maxAttachmentBytes is never sent.
	oversized := make([]byte, maxAttachmentBytes+1)
	if err := os.WriteFile(filepath.Join(spool, "attachment-big.bin"), oversized, 0o600); err != nil {
		t.Fatalf("write oversized: %v", err)
	}
	bigMeta := `{"attachmentId":"big","kind":"image","contentType":"image/png"}`
	if err := os.WriteFile(filepath.Join(spool, "attachment-big.bin.meta.json"), []byte(bigMeta), 0o600); err != nil {
		t.Fatalf("write big meta: %v", err)
	}
	// Unsupported kind (pdf) is rejected locally.
	if err := os.WriteFile(filepath.Join(spool, "attachment-doc.bin"), []byte("%PDF-1.4"), 0o600); err != nil {
		t.Fatalf("write pdf: %v", err)
	}
	pdfMeta := `{"attachmentId":"doc","kind":"pdf"}`
	if err := os.WriteFile(filepath.Join(spool, "attachment-doc.bin.meta.json"), []byte(pdfMeta), 0o600); err != nil {
		t.Fatalf("write pdf meta: %v", err)
	}

	var resultsMu sync.Mutex
	var results []PublishResult
	poller := c.StartPublishPolling(func(r PublishResult) {
		resultsMu.Lock()
		defer resultsMu.Unlock()
		results = append(results, r)
	})
	if poller == nil {
		t.Fatal("expected a poller")
	}
	defer poller.Stop()

	waitForResults(t, &resultsMu, &results, 2)

	resultsMu.Lock()
	snapshot := append([]PublishResult(nil), results...)
	resultsMu.Unlock()

	if !strings.Contains(snapshot[0].Error, "exceeds") && !strings.Contains(snapshot[1].Error, "exceeds") {
		t.Errorf("expected an oversize rejection, got %+v", snapshot)
	}
	if !strings.Contains(snapshot[0].Error, "unsupported") && !strings.Contains(snapshot[1].Error, "unsupported") {
		t.Errorf("expected a kind rejection, got %+v", snapshot)
	}
	if len(frames.snapshot()) != 0 {
		t.Errorf("expected no frames sent for rejected entries, got %d", len(frames.snapshot()))
	}
}

func TestPublishPoller_SkipsEntryWithoutActiveExecution(t *testing.T) {
	srv, frames := startPublishTestServer(t)
	c := startPublishClient(t, srv)
	// No active execution: the poller must skip the doomed request rather than send it.
	c.mu.Lock()
	c.currentExecutionID = ""
	c.mu.Unlock()

	spool := c.publishSpoolDir()
	if err := os.MkdirAll(spool, 0o750); err != nil {
		t.Fatalf("mkdir spool: %v", err)
	}
	manifest := `{"port":3000,"label":"Web app"}`
	if err := os.WriteFile(filepath.Join(spool, "preview-2.json"), []byte(manifest), 0o600); err != nil {
		t.Fatalf("write manifest: %v", err)
	}

	poller := c.StartPublishPolling(nil)
	if poller == nil {
		t.Fatal("expected a poller")
	}
	defer poller.Stop()

	// Give the poller a couple of drain cycles; it must never send env.preview.
	time.Sleep(700 * time.Millisecond)
	if len(frames.snapshot()) != 0 {
		t.Errorf("expected no frames without an active execution, got %+v", frames.snapshot())
	}
	waitForFilePresent(t, filepath.Join(spool, "done", "preview-2.json"))
}

// waitForFrames polls until the captured frames contain wantMethod at least wantCount times.
func waitForFrames(t *testing.T, frames *frameCollector, wantCount int, wantMethod string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		count := 0
		for _, f := range frames.snapshot() {
			if f.Method == wantMethod {
				count++
			}
		}
		if count >= wantCount {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %d %s frames; got %+v", wantCount, wantMethod, frames.snapshot())
}

// waitForResults polls until the collected results reach wantCount.
func waitForResults(t *testing.T, mu *sync.Mutex, results *[]PublishResult, wantCount int) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		n := len(*results)
		mu.Unlock()
		if n >= wantCount {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	mu.Lock()
	defer mu.Unlock()
	t.Fatalf("timed out waiting for %d results; got %+v", wantCount, *results)
}

func waitForFileAbsent(t *testing.T, path string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := os.Stat(path); os.IsNotExist(err) {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Errorf("expected %s to be gone", path)
}

func waitForFilePresent(t *testing.T, path string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := os.Stat(path); err == nil {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Errorf("expected %s to exist", path)
}

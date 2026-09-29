package rpc

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/gorilla/websocket"
)

// TestChunkString_RuneSafe verifies chunkString splits without cutting a
// multi-byte rune and reassembles to the original input.
func TestChunkString_RuneSafe(t *testing.T) {
	line := strings.Repeat("é", 10000) // 20000 bytes
	chunks := chunkString(line, 1000)
	if len(chunks) < 2 {
		t.Fatalf("expected multiple chunks, got %d", len(chunks))
	}
	var rebuilt strings.Builder
	for _, ch := range chunks {
		if !utf8.ValidString(ch) {
			t.Fatalf("chunk is invalid UTF-8: %q", ch)
		}
		if len(ch) > 1000 {
			t.Errorf("chunk exceeds 1000 bytes, got %d", len(ch))
		}
		rebuilt.WriteString(ch)
	}
	if rebuilt.String() != line {
		t.Error("expected chunks to reassemble the original line")
	}

	// Limit inside the first multi-byte rune must not split it or stall.
	multi := "ééa"
	chunks = chunkString(multi, 1)
	if len(chunks) < 2 {
		t.Fatalf("expected multiple chunks for %q with limit 1, got %d", multi, len(chunks))
	}
	rebuilt.Reset()
	for _, ch := range chunks {
		if !utf8.ValidString(ch) {
			t.Fatalf("chunk is invalid UTF-8: %q", ch)
		}
		if len(ch) == 0 {
			t.Fatalf("chunkString produced a zero-length chunk (no progress): %q", ch)
		}
		rebuilt.WriteString(ch)
	}
	if rebuilt.String() != multi {
		t.Errorf("expected chunks to reassemble %q, got %q", multi, rebuilt.String())
	}

	// Limit inside a lone multi-byte rune must still make progress.
	single := chunkString("é", 1)
	if len(single) != 1 || single[0] != "é" {
		t.Errorf("expected single chunk %q, got %+v", "é", single)
	}
}

// TestSendOutput_ChunksOversizedLine verifies a single streamed line larger
// than maxOutputChunkBytes is split into multiple env.output frames and the
// control plane receives the full payload reassembled.
func TestSendOutput_ChunksOversizedLine(t *testing.T) {
	var (
		mu     sync.Mutex
		got    strings.Builder
		count  int
		doneCh = make(chan struct{})
	)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				continue
			}
			if req.Method != "env.output" {
				continue
			}
			var params OutputParams
			b, _ := json.Marshal(req.Params)
			_ = json.Unmarshal(b, &params)
			mu.Lock()
			got.WriteString(params.Line)
			count++
			if count == 3 {
				close(doneCh)
			}
			mu.Unlock()
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	defer c.Close()

	line := strings.Repeat("L", 10*1024*1024) // 10MB, over maxOutputChunkBytes
	c.SendOutput(line, "stdout")

	select {
	case <-doneCh:
	case <-time.After(5 * time.Second):
		t.Fatal("timeout waiting for chunked env.output frames")
	}

	mu.Lock()
	defer mu.Unlock()
	if got.String() != line {
		t.Errorf("expected reassembled output to match the original, got %d bytes", got.Len())
	}
}

// TestWriteJSON_FrameTooLargeRejected verifies the transport refuses to send a
// frame above maxOutboundFrameBytes instead of letting Tomcat close the
// connection with 1009.
func TestWriteJSON_FrameTooLargeRejected(t *testing.T) {
	received := make(chan struct{}, 1)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		if _, _, err := conn.ReadMessage(); err == nil {
			received <- struct{}{}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	defer c.Close()

	big := map[string]interface{}{"data": strings.Repeat("x", maxOutboundFrameBytes+1)}
	err := c.writeJSON(big)
	if err == nil {
		t.Fatal("expected frame-too-large error")
	}
	if !strings.Contains(err.Error(), "frame too large") {
		t.Errorf("unexpected error message: %v", err)
	}

	select {
	case <-received:
		t.Error("expected the control plane to receive nothing for an oversized frame")
	case <-time.After(200 * time.Millisecond):
	}
}

// TestSendNotification_FrameDropReportsSidecarError verifies that dropping an
// oversized notification also broadcasts env.sidecar_error (kind=frame_dropped)
// to the control plane, so data loss is never silent.
func TestSendNotification_FrameDropReportsSidecarError(t *testing.T) {
	gotError := make(chan SidecarErrorParams, 1)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				continue
			}
			if req.Method != "env.sidecar_error" {
				continue
			}
			b, _ := json.Marshal(req.Params)
			var params SidecarErrorParams
			_ = json.Unmarshal(b, &params)
			gotError <- params
			return
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	defer c.Close()

	err := c.sendNotification("env.output", OutputParams{Line: strings.Repeat("x", maxOutboundFrameBytes+1)})
	if err == nil {
		t.Fatal("expected oversized notification to be rejected")
	}

	select {
	case params := <-gotError:
		if params.Kind != SidecarErrorFrameDropped {
			t.Errorf("expected kind frame_dropped, got %q", params.Kind)
		}
		if !strings.Contains(params.Message, "env.output") {
			t.Errorf("expected message to name env.output, got %q", params.Message)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timeout waiting for env.sidecar_error frame_dropped report")
	}
}

// TestClient_Start_ReportsDisconnectAfterReconnect verifies that an unexpected
// close from the control plane (e.g. 1009 message-too-big) is reported back as
// env.sidecar_error (kind=connection_closed) after the sidecar reconnects and
// re-registers — never only a sidecar-local log line.
func TestClient_Start_ReportsDisconnectAfterReconnect(t *testing.T) {
	reported := make(chan SidecarErrorParams, 1)
	var firstClose atomic.Bool

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if err := json.Unmarshal(msg, &req); err != nil {
				continue
			}
			switch req.Method {
			case "env.register":
				raw, _ := json.Marshal(RegisterResult{Type: "env_register", Status: "registered", EnvironmentID: "env-123"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: raw, ID: req.ID})
				if firstClose.CompareAndSwap(false, true) {
					// First connection: close with 1009 to trigger reconnect.
					_ = conn.WriteMessage(
						websocket.CloseMessage,
						websocket.FormatCloseMessage(websocket.CloseMessageTooBig, "message too big"))
				}
			case "env.sidecar_error":
				b, _ := json.Marshal(req.Params)
				var params SidecarErrorParams
				_ = json.Unmarshal(b, &params)
				reported <- params
			}
		}
	})

	c := NewClient(wsURL(srv), "tok", "", "")
	setTestClientTimeouts(c)
	ctx, cancel := context.WithCancel(context.Background())
	go c.Start(ctx)
	defer cancel()

	select {
	case params := <-reported:
		if params.Kind != SidecarErrorConnectionClosed {
			t.Errorf("expected kind connection_closed, got %q", params.Kind)
		}
		if params.CloseCode == nil || *params.CloseCode != websocket.CloseMessageTooBig {
			t.Errorf("expected closeCode 1009, got %v", params.CloseCode)
		}
		if !strings.Contains(params.Message, "reconnected") {
			t.Errorf("expected reconnect message, got %q", params.Message)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("timeout waiting for env.sidecar_error connection_closed report after reconnect")
	}
}

// TestReadLoop_ServerCloseReportsError verifies an unexpected server close
// (e.g. Tomcat close 1009 message-too-big) surfaces as a CloseError on the
// read loop so Start can log and reconnect.
func TestReadLoop_ServerCloseReportsError(t *testing.T) {
	var serverConn *websocket.Conn
	ready := make(chan struct{})

	srv := newTestServer(t, func(conn *websocket.Conn) {
		serverConn = conn
		close(ready)
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	<-ready

	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	// Server closes with 1009 (message too big), mirroring Tomcat.
	if err := serverConn.WriteMessage(
		websocket.CloseMessage,
		websocket.FormatCloseMessage(websocket.CloseMessageTooBig, "message too big")); err != nil {
		t.Fatalf("server write: %v", err)
	}

	select {
	case err := <-errChan:
		var closeErr *websocket.CloseError
		if !errors.As(err, &closeErr) {
			t.Fatalf("expected *websocket.CloseError, got %T: %v", err, err)
		}
		if closeErr.Code != websocket.CloseMessageTooBig {
			t.Errorf("expected close code 1009, got %d", closeErr.Code)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timeout waiting for readLoop close error")
	}

	c.Close()
}

// TestCloseConnection_FailsPendingFast verifies an unexpected disconnect
// delivers a synthetic error response to every in-flight RPC waiter and
// clears the pending map.
func TestCloseConnection_FailsPendingFast(t *testing.T) {
	c := connectClient(t, wsURL(newTestServer(t, func(conn *websocket.Conn) {
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	})), "tok")

	ch := make(chan *JsonRpcResponse, 1)
	c.mu.Lock()
	c.pending[uint64(42)] = ch
	c.mu.Unlock()

	c.closeConnection()

	select {
	case resp := <-ch:
		if resp == nil {
			t.Fatal("received nil response")
		}
		if resp.Error == nil {
			t.Fatal("expected synthetic error response")
		}
		if resp.Error.Code != -32000 {
			t.Errorf("expected code -32000, got %d", resp.Error.Code)
		}
		if resp.Error.Message != errConnectionClosed.Error() {
			t.Errorf("unexpected error message: %q", resp.Error.Message)
		}
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for synthetic error to pending waiter")
	}

	c.mu.Lock()
	pendingLen := len(c.pending)
	c.mu.Unlock()
	if pendingLen != 0 {
		t.Errorf("expected pending map to be cleared, got %d entries", pendingLen)
	}
	if c.wsConn != nil {
		t.Error("expected wsConn to be nil after closeConnection")
	}
}

// TestSendRequest_FailsFastAfterUnexpectedClose verifies sendRequest returns
// promptly with an error response after the connection drops, instead of
// waiting out the request timeout.
func TestSendRequest_FailsFastAfterUnexpectedClose(t *testing.T) {
	received := make(chan struct{}, 1)

	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil {
				continue
			}
			if req.Method == "env.heartbeat" {
				received <- struct{}{}
			}
		}
	})

	c := connectClient(t, wsURL(srv), "tok")
	c.requestTimeout = 5 * time.Second
	errChan := make(chan error, 1)
	go c.readLoop(errChan)

	type result struct {
		resp *JsonRpcResponse
		err  error
	}
	done := make(chan result, 1)
	go func() {
		resp, err := c.sendRequest("env.heartbeat", HeartbeatParams{})
		done <- result{resp: resp, err: err}
	}()

	// Wait until the request is on the wire before dropping the connection.
	select {
	case <-received:
	case <-time.After(2 * time.Second):
		t.Fatal("timeout waiting for heartbeat request to reach the server")
	}
	c.closeConnection()

	select {
	case res := <-done:
		if res.resp == nil || res.resp.Error == nil {
			t.Fatalf("expected synthetic error response, got resp=%+v err=%v", res.resp, res.err)
		}
		if res.resp.Error.Code != -32000 {
			t.Errorf("expected code -32000, got %d", res.resp.Error.Code)
		}
	case <-time.After(1 * time.Second):
		t.Fatal("sendRequest did not fail fast after unexpected close")
	}
}

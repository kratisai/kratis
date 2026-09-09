package acp

import (
	"encoding/json"
	"testing"
)

func TestNewInitializeRequest_HasAllRequiredFields(t *testing.T) {
	req := NewInitializeRequest()

	if req.ProtocolVersion != AcpProtocolVersion {
		t.Errorf("expected protocolVersion %d, got %d", AcpProtocolVersion, req.ProtocolVersion)
	}

	// Gap 1: writeTextFile must be true
	if !req.ClientCapabilities.Fs.ReadTextFile {
		t.Error("expected fs.readTextFile to be true")
	}
	if !req.ClientCapabilities.Fs.WriteTextFile {
		t.Error("expected fs.writeTextFile to be true (Gap 1 fix)")
	}

	// Terminal capability
	if !req.ClientCapabilities.Terminal {
		t.Error("expected terminal to be true")
	}

	// Agent-owned terminal relay capability (P1)
	if req.ClientCapabilities.Meta == nil || req.ClientCapabilities.Meta["terminal_output"] != true {
		t.Error("expected _meta.terminal_output capability advertised")
	}

	// Structured elicitation capability (form mode)
	elic, ok := req.ClientCapabilities.Meta["elicitation"].(map[string]any)
	if !ok || elic["form"] != true {
		t.Errorf("expected _meta.elicitation.form capability advertised, got %v", req.ClientCapabilities.Meta["elicitation"])
	}

	// Gap 2: title must be present
	if req.ClientInfo.Name != "kratis" {
		t.Errorf("expected clientInfo.name 'kratis', got '%s'", req.ClientInfo.Name)
	}
	if req.ClientInfo.Title != "Kratis AI" {
		t.Errorf("expected clientInfo.title 'Kratis AI' (Gap 2 fix), got '%s'", req.ClientInfo.Title)
	}
	if req.ClientInfo.Version != "1.0.0" {
		t.Errorf("expected clientInfo.version '1.0.0', got '%s'", req.ClientInfo.Version)
	}
}

func TestNewInitializeRequest_JSONMarshalling(t *testing.T) {
	req := NewInitializeRequest()
	data, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("failed to marshal: %v", err)
	}

	var raw map[string]interface{}
	if err := json.Unmarshal(data, &raw); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}

	// Verify protocolVersion
	if pv, ok := raw["protocolVersion"].(float64); !ok || int(pv) != 1 {
		t.Errorf("expected protocolVersion 1 in JSON, got %v", raw["protocolVersion"])
	}

	// Verify clientCapabilities.fs.writeTextFile
	cc, ok := raw["clientCapabilities"].(map[string]interface{})
	if !ok {
		t.Fatal("clientCapabilities missing or not a map")
	}
	fs, ok := cc["fs"].(map[string]interface{})
	if !ok {
		t.Fatal("clientCapabilities.fs missing or not a map")
	}
	if wt, ok := fs["writeTextFile"].(bool); !ok || !wt {
		t.Error("expected writeTextFile true in JSON output")
	}
	if rt, ok := fs["readTextFile"].(bool); !ok || !rt {
		t.Error("expected readTextFile true in JSON output")
	}

	// Verify clientInfo.title
	ci, ok := cc["terminal"]
	if !ok {
		t.Error("expected terminal in clientCapabilities")
	}
	_ = ci

	// Verify _meta.terminal_output capability marshals into clientCapabilities
	meta, ok := cc["_meta"].(map[string]interface{})
	if !ok {
		t.Fatal("expected _meta in clientCapabilities JSON")
	}
	if to, ok := meta["terminal_output"].(bool); !ok || !to {
		t.Error("expected _meta.terminal_output true in JSON output")
	}

	ci2, ok := raw["clientInfo"].(map[string]interface{})
	if !ok {
		t.Fatal("clientInfo missing or not a map")
	}
	if title, ok := ci2["title"].(string); !ok || title != "Kratis AI" {
		t.Errorf("expected title 'Kratis AI' in JSON, got %v", ci2["title"])
	}
}

func TestNegotiateVersion_MatchingVersion(t *testing.T) {
	agreed, err := NegotiateVersion(1, 1)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if agreed != 1 {
		t.Errorf("expected agreed version 1, got %d", agreed)
	}
}

func TestNegotiateVersion_AgentOffersLower(t *testing.T) {
	agreed, err := NegotiateVersion(2, 1)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if agreed != 1 {
		t.Errorf("expected agreed version 1 (agent's lower version), got %d", agreed)
	}
}

func TestNegotiateVersion_AgentOffersHigher(t *testing.T) {
	_, err := NegotiateVersion(1, 2)
	if err == nil {
		t.Error("expected error when agent offers higher version than client supports")
	}
}

func TestNegotiateVersion_AgentOffersZero(t *testing.T) {
	_, err := NegotiateVersion(1, 0)
	if err == nil {
		t.Error("expected error when agent offers version 0")
	}
}

func TestAcpInitializeResponse_JSONUnmarshalling(t *testing.T) {
	jsonStr := `{
		"protocolVersion": 1,
		"agentCapabilities": {
			"loadSession": true,
			"promptCapabilities": {
				"image": true,
				"audio": false,
				"embeddedContext": true
			},
			"mcpCapabilities": {
				"http": true,
				"sse": false
			}
		},
		"agentInfo": {
			"name": "test-agent",
			"title": "Test Agent",
			"version": "2.0.0"
		},
		"authMethods": [{"type": "oauth2"}]
	}`

	var resp AcpInitializeResponse
	if err := json.Unmarshal([]byte(jsonStr), &resp); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}

	if resp.ProtocolVersion != 1 {
		t.Errorf("expected protocolVersion 1, got %d", resp.ProtocolVersion)
	}
	if !resp.AgentCapabilities.LoadSession {
		t.Error("expected loadSession true")
	}
	if resp.AgentCapabilities.PromptCapabilities == nil {
		t.Fatal("expected promptCapabilities to be non-nil")
	}
	if !resp.AgentCapabilities.PromptCapabilities.Image {
		t.Error("expected image capability true")
	}
	if resp.AgentCapabilities.PromptCapabilities.Audio {
		t.Error("expected audio capability false")
	}
	if !resp.AgentCapabilities.PromptCapabilities.EmbeddedContext {
		t.Error("expected embeddedContext true")
	}
	if resp.AgentCapabilities.McpCapabilities == nil {
		t.Fatal("expected mcpCapabilities to be non-nil")
	}
	if !resp.AgentCapabilities.McpCapabilities.HTTP {
		t.Error("expected MCP HTTP true")
	}
	if resp.AgentInfo.Name != "test-agent" {
		t.Errorf("expected agent name 'test-agent', got '%s'", resp.AgentInfo.Name)
	}
	if resp.AgentInfo.Title != "Test Agent" {
		t.Errorf("expected agent title 'Test Agent', got '%s'", resp.AgentInfo.Title)
	}
	if resp.AgentInfo.Version != "2.0.0" {
		t.Errorf("expected agent version '2.0.0', got '%s'", resp.AgentInfo.Version)
	}
	if len(resp.AuthMethods) != 1 {
		t.Errorf("expected 1 auth method, got %d", len(resp.AuthMethods))
	}
}

func TestAcpSessionNewRequest_JSONMarshalling(t *testing.T) {
	req := AcpSessionNewRequest{
		Cwd:        "/workspace",
		McpServers: []interface{}{},
	}
	data, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("failed to marshal: %v", err)
	}

	var raw map[string]interface{}
	if err := json.Unmarshal(data, &raw); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}

	if raw["cwd"] != "/workspace" {
		t.Errorf("expected cwd '/workspace', got %v", raw["cwd"])
	}
}

func TestAcpSessionCancelRequest_JSONMarshalling(t *testing.T) {
	req := AcpSessionCancelRequest{
		SessionID: "sess-123",
	}
	data, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("failed to marshal: %v", err)
	}

	var raw map[string]interface{}
	if err := json.Unmarshal(data, &raw); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}

	if raw["sessionId"] != "sess-123" {
		t.Errorf("expected sessionId 'sess-123', got %v", raw["sessionId"])
	}
}

func TestAcpAgentCapabilities_OptionalFields(t *testing.T) {
	// Test with minimal capabilities (no optional fields)
	jsonStr := `{"loadSession": false}`
	var caps AcpAgentCapabilities
	if err := json.Unmarshal([]byte(jsonStr), &caps); err != nil {
		t.Fatalf("failed to unmarshal: %v", err)
	}
	if caps.LoadSession {
		t.Error("expected loadSession false")
	}
	if caps.PromptCapabilities != nil {
		t.Error("expected promptCapabilities to be nil")
	}
	if caps.McpCapabilities != nil {
		t.Error("expected mcpCapabilities to be nil")
	}
	if caps.Auth != nil {
		t.Error("expected auth to be nil")
	}
	if caps.Session != nil {
		t.Error("expected session to be nil")
	}
}

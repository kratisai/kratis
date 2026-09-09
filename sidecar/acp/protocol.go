package acp

import (
	"encoding/json"
	"fmt"
)

const AcpProtocolVersion = 1

type AcpInitializeRequest struct {
	ProtocolVersion    int                   `json:"protocolVersion"`
	ClientCapabilities AcpClientCapabilities `json:"clientCapabilities"`
	ClientInfo         AcpClientInfo         `json:"clientInfo"`
}

type AcpClientCapabilities struct {
	Fs       AcpFsCapabilities `json:"fs"`
	Terminal bool              `json:"terminal"`
	// Meta carries protocol extension capabilities in the ACP _meta bag.
	Meta map[string]any `json:"_meta,omitempty"`
}

type AcpFsCapabilities struct {
	ReadTextFile  bool `json:"readTextFile"`
	WriteTextFile bool `json:"writeTextFile"`
}

type AcpClientInfo struct {
	Name    string `json:"name"`
	Title   string `json:"title"`
	Version string `json:"version"`
}

type AcpSessionNewRequest struct {
	Cwd        string        `json:"cwd"`
	McpServers []interface{} `json:"mcpServers"`
}

type AcpSessionPromptRequest struct {
	SessionID string        `json:"sessionId"`
	Prompt    []interface{} `json:"prompt"`
}

type AcpSessionCancelRequest struct {
	SessionID string `json:"sessionId"`
}

type AcpInitializeResponse struct {
	ProtocolVersion   int                  `json:"protocolVersion"`
	AgentCapabilities AcpAgentCapabilities `json:"agentCapabilities"`
	AgentInfo         AcpAgentInfo         `json:"agentInfo"`
	AuthMethods       []interface{}        `json:"authMethods"`
}

type AcpAgentCapabilities struct {
	LoadSession        bool                    `json:"loadSession"`
	PromptCapabilities *AcpPromptCapabilities  `json:"promptCapabilities,omitempty"`
	McpCapabilities    *AcpMcpCapabilities     `json:"mcpCapabilities,omitempty"`
	Auth               *AcpAuthCapabilities    `json:"auth,omitempty"`
	Session            *AcpSessionCapabilities `json:"session,omitempty"`
}

type AcpPromptCapabilities struct {
	Image           bool `json:"image"`
	Audio           bool `json:"audio"`
	EmbeddedContext bool `json:"embeddedContext"`
}

type AcpMcpCapabilities struct {
	HTTP bool `json:"http"`
	SSE  bool `json:"sse"`
}

type AcpAuthCapabilities struct {
	Schemes []interface{} `json:"schemes,omitempty"`
}

type AcpSessionCapabilities struct {
	Load bool `json:"load"`
}

type AcpAgentInfo struct {
	Name    string `json:"name"`
	Title   string `json:"title"`
	Version string `json:"version"`
}

func NewInitializeRequest() AcpInitializeRequest {
	return AcpInitializeRequest{
		ProtocolVersion: AcpProtocolVersion,
		ClientCapabilities: AcpClientCapabilities{
			Fs: AcpFsCapabilities{
				ReadTextFile:  true,
				WriteTextFile: true,
			},
			Terminal: true,
			Meta: map[string]any{
				"terminal_output": true,
				"elicitation":     map[string]any{"form": true},
			},
		},
		ClientInfo: AcpClientInfo{
			Name:    "kratis",
			Title:   "Kratis AI",
			Version: "1.0.0",
		},
	}
}

func NegotiateVersion(requested, offered int) (int, error) {
	if offered == requested {
		return requested, nil
	}
	if offered > 0 && offered < requested {
		return offered, nil
	}
	return 0, fmt.Errorf("ACP version negotiation failed: client supports %d, agent offered %d", requested, offered)
}

func ParseInitializeResponse(resp map[string]interface{}) (AcpInitializeResponse, string, string, error) {
	var result AcpInitializeResponse

	resultMap, ok := resp["result"].(map[string]interface{})
	if !ok {
		return result, "", "", fmt.Errorf("no result in initialize response")
	}

	resultBytes, err := json.Marshal(resultMap)
	if err != nil {
		return result, "", "", fmt.Errorf("failed to marshal result: %w", err)
	}
	if err := json.Unmarshal(resultBytes, &result); err != nil {
		return result, "", "", fmt.Errorf("failed to parse initialize response: %w", err)
	}

	agentName := result.AgentInfo.Name
	agentVersion := result.AgentInfo.Version
	if agentName == "" {
		if serverInfo, ok := resultMap["serverInfo"].(map[string]interface{}); ok {
			agentName, _ = serverInfo["name"].(string)
			agentVersion, _ = serverInfo["version"].(string)
		}
	}

	return result, agentName, agentVersion, nil
}

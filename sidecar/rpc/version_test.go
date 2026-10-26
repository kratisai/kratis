package rpc

import (
	"context"
	"encoding/json"
	"runtime/debug"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

const (
	sha         = "27e6906492b527b2af1adfd126cafa01de4dc84d"
	shortCommit = "27e6906492b5"
	buildStamp  = "2026-10-17T09:43:54Z"
)

func buildInfoWith(settings ...debug.BuildSetting) *debug.BuildInfo {
	return &debug.BuildInfo{Settings: settings}
}

func vcsSettings(revision, modified, commitTime string) *debug.BuildInfo {
	return buildInfoWith(
		debug.BuildSetting{Key: "vcs.revision", Value: revision},
		debug.BuildSetting{Key: "vcs.time", Value: commitTime},
		debug.BuildSetting{Key: "vcs.modified", Value: modified})
}

func TestFormatConnectorVersion(t *testing.T) {
	tests := []struct {
		name          string
		stampedCommit string
		stampedTime   string
		info          *debug.BuildInfo
		haveInfo      bool
		want          string
	}{
		{"stamped commit and build time", "abcdef0123456789abcdef", buildStamp, nil, false,
			"abcdef012345" + "+" + buildStamp},
		{"embedded revision with stamped build time", "", buildStamp, vcsSettings(sha, "false", buildStamp), true,
			shortCommit + "+" + buildStamp},
		{"modified tree is marked dirty", "", buildStamp, vcsSettings(sha, "true", buildStamp), true,
			shortCommit + "+" + buildStamp + dirtySuffix},
		{"no commit is unknown plus build time", "", buildStamp, buildInfoWith(), true,
			unknownPart + "+" + buildStamp},
		{"embedded commit time is not used as the build time", "", "", vcsSettings(sha, "false", buildStamp), true,
			shortCommit + "+" + unknownPart},
		{"no build time is unknown", "abcdef0123456789", "", nil, false, "abcdef012345+" + unknownPart},
		{"nothing known", "", "", buildInfoWith(), true, unknownPart + "+" + unknownPart},
		{"stamped offset time is normalised to UTC", "abcdef0123456789", "2026-10-17T10:43:54+01:00", nil, false,
			"abcdef012345+" + buildStamp},
		{"unparseable stamped time is kept raw", "abcdef0123456789", "not-a-time", nil, false,
			"abcdef012345+not-a-time"},
		{"short stamped commit is kept whole", " abc123 ", buildStamp, nil, false, "abc123+" + buildStamp},
		{"no build info is unknown plus build time", "", buildStamp, nil, false, unknownPart + "+" + buildStamp},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := formatConnectorVersion(tc.stampedCommit, tc.stampedTime, tc.info, tc.haveInfo); got != tc.want {
				t.Errorf("Expected %q, got %q", tc.want, got)
			}
		})
	}
}

func TestConnectorVersion_UsesStampedBuildTime(t *testing.T) {
	previousCommit, previousTime := buildCommit, buildTime
	t.Cleanup(func() { buildCommit, buildTime = previousCommit, previousTime })
	buildCommit, buildTime = "deadbeefcafe0123", "2026-01-02T03:04:05+00:00"

	if got := ConnectorVersion(); got != "deadbeefcafe+2026-01-02T03:04:05Z" {
		t.Errorf("Expected stamped commit and normalised build time, got %q", got)
	}
}

func TestConnectorVersion_IsAlwaysCommitPlusBuildTime(t *testing.T) {
	got := ConnectorVersion()
	parts := strings.SplitN(strings.TrimSuffix(got, dirtySuffix), stampSeparator, 2)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		t.Fatalf("Expected <commit>%s<build-time>[%s], got %q", stampSeparator, dirtySuffix, got)
	}
	if parts[0] != unknownPart && len(parts[0]) > shortCommitLength {
		t.Errorf("Expected a shortened commit or %q, got %q", unknownPart, parts[0])
	}
	if parts[1] != unknownPart {
		if _, err := time.Parse(buildTimeLayout, parts[1]); err != nil {
			t.Errorf("Expected %q to be a build time or %q, got %q", unknownPart, err, parts[1])
		}
	}
}

// TestRegister_ReportsConnectorVersion pins the wire contract the control plane logs on every
// (re-)registration: the version of the binary that was copied into the sandbox.
func TestRegister_ReportsConnectorVersion(t *testing.T) {
	previousCommit, previousTime := buildCommit, buildTime
	t.Cleanup(func() { buildCommit, buildTime = previousCommit, previousTime })
	buildCommit, buildTime = "0123456789abcdef0123", "2026-01-02T03:04:05Z"

	registered := make(chan RegisterParams, 4)
	srv := newTestServer(t, func(conn *websocket.Conn) {
		defer func() { _ = conn.Close() }()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			var req JsonRpcRequest
			if json.Unmarshal(msg, &req) != nil || req.Method != "env.register" {
				continue
			}
			var params RegisterParams
			raw, _ := json.Marshal(req.Params)
			_ = json.Unmarshal(raw, &params)
			registered <- params
			result, _ := json.Marshal(RegisterResult{Type: "env_register", Status: "registered", EnvironmentID: "env-1"})
			_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: result, ID: req.ID})
		}
	})

	client := NewClient(wsURL(srv), "valid-token", t.TempDir())
	setTestClientTimeouts(client)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go client.Start(ctx)

	select {
	case params := <-registered:
		if params.ConnectorVersion != "0123456789ab+2026-01-02T03:04:05Z" {
			t.Errorf("Expected register to carry the stamped version, got %q", params.ConnectorVersion)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for env.register")
	}
	client.Close()
}

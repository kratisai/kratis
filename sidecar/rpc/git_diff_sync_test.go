package rpc

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestIncrementalDiffSync_ManifestAndSectionsFlow(t *testing.T) {
	dir := setupTestGitRepo(t)
	defer func() { _ = os.RemoveAll(dir) }()

	var mu sync.Mutex
	manifestReceived := 0
	sectionsReceived := 0
	storedBlobs := make(map[string]bool)

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			t.Errorf("upgrade error: %v", err)
			return
		}
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
				res, _ := json.Marshal(RegisterResult{Type: "env_register", Status: "registered", EnvironmentID: "env-1"})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: res, ID: req.ID})
			case "env.diff_changed":
				ackDiffChanged(conn, req)
			case "env.diff_manifest":
				paramsBytes, _ := json.Marshal(req.Params)
				var params DiffManifestParams
				_ = json.Unmarshal(paramsBytes, &params)
				mu.Lock()
				manifestReceived++
				var missing []string
				for _, f := range params.Files {
					if !storedBlobs[f.Sha] {
						missing = append(missing, f.Sha)
					}
				}
				status := DiffManifestStatusCommitted
				if len(missing) > 0 {
					status = DiffManifestStatusIncomplete
				}
				mu.Unlock()

				res, _ := json.Marshal(DiffManifestResult{
					Type:    DiffManifestResultTypeEnvDiffManifest,
					Status:  status,
					Missing: missing,
				})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: res, ID: req.ID})
			case "env.diff_sections":
				paramsBytes, _ := json.Marshal(req.Params)
				var params DiffSectionsParams
				_ = json.Unmarshal(paramsBytes, &params)
				mu.Lock()
				sectionsReceived++
				for _, p := range params.Parts {
					storedBlobs[p.Sha] = true
				}
				mu.Unlock()

				res, _ := json.Marshal(DiffSectionsResult{
					Type:    DiffSectionsResultTypeEnvDiffSections,
					Status:  DiffSectionsStatusStored,
					Missing: []string{},
				})
				_ = conn.WriteJSON(JsonRpcResponse{JsonRPC: "2.0", Result: res, ID: req.ID})
			}
		}
	}))
	defer srv.Close()

	client := connectClient(t, wsURL(srv), "token")
	defer client.Close()
	setTestClientTimeouts(client)
	client.workspace = dir
	client.currentExecutionID = "exec-sync"
	client.baseBranch = "HEAD"
	errChan := make(chan error, 1)
	go client.readLoop(errChan)

	if err := os.WriteFile(filepath.Join(dir, "change.txt"), []byte("diff content\n"), 0600); err != nil {
		t.Fatalf("write file: %v", err)
	}

	client.emitDiffChangedSync()

	time.Sleep(100 * time.Millisecond)

	mu.Lock()
	mCount := manifestReceived
	sCount := sectionsReceived
	mu.Unlock()

	if mCount < 2 {
		t.Errorf("expected at least 2 manifest requests (initial check + final commit), got %d", mCount)
	}
	if sCount < 1 {
		t.Errorf("expected at least 1 sections request, got %d", sCount)
	}
}

func TestManifestDigestChangesOnContentEdit(t *testing.T) {
	dir := setupTestGitRepo(t)
	defer func() { _ = os.RemoveAll(dir) }()

	client := &Client{workspace: dir}

	filePath := filepath.Join(dir, "README.md")
	if err := os.WriteFile(filePath, []byte("# Edit 1\n"), 0600); err != nil {
		t.Fatalf("write edit 1: %v", err)
	}

	_, m1, _, err := client.buildDiffSections("HEAD")
	if err != nil {
		t.Fatalf("build 1: %v", err)
	}

	if err := os.WriteFile(filePath, []byte("# Edit 2\n"), 0600); err != nil {
		t.Fatalf("write edit 2: %v", err)
	}

	_, m2, _, err := client.buildDiffSections("HEAD")
	if err != nil {
		t.Fatalf("build 2: %v", err)
	}

	if m1.ManifestDigest == m2.ManifestDigest {
		t.Errorf("manifestDigest must change when file content changes, got same: %s", m1.ManifestDigest)
	}
}

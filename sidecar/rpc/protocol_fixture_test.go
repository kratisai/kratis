package rpc

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestEnvironmentFixturesRoundTrip(t *testing.T) {
	validFiles, err := filepath.Glob("../../protocol/environment/examples/valid/*.json")
	if err != nil || len(validFiles) == 0 {
		// Fall back to relative path from sidecar directory
		validFiles, err = filepath.Glob("../protocol/environment/examples/valid/*.json")
		if err != nil || len(validFiles) == 0 {
			t.Skip("Valid fixtures directory not found relative to test runner")
		}
	}

	for _, file := range validFiles {
		t.Run(filepath.Base(file), func(t *testing.T) {
			data, err := os.ReadFile(file)
			if err != nil {
				t.Fatalf("Failed to read fixture %s: %v", file, err)
			}

			var req JsonRpcRequest
			if err := json.Unmarshal(data, &req); err != nil {
				t.Fatalf("Failed to unmarshal JsonRpcRequest fixture %s: %v", file, err)
			}

			out, err := json.Marshal(req)
			if err != nil {
				t.Fatalf("Failed to marshal JsonRpcRequest: %v", err)
			}

			var originalMap, roundTripMap map[string]interface{}
			if err := json.Unmarshal(data, &originalMap); err != nil {
				t.Fatalf("Failed to parse original JSON: %v", err)
			}
			if err := json.Unmarshal(out, &roundTripMap); err != nil {
				t.Fatalf("Failed to parse roundtrip JSON: %v", err)
			}

			if originalMap["method"] != roundTripMap["method"] {
				t.Errorf("Method mismatch: got %v, want %v", roundTripMap["method"], originalMap["method"])
			}
		})
	}
}

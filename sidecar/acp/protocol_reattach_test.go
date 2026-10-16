package acp

import (
	"encoding/json"
	"testing"
)

func TestSupportsResume(t *testing.T) {
	cases := []struct {
		name string
		json string
		want bool
	}{
		{"resume advertised", `{"sessionCapabilities":{"resume":{}}}`, true},
		{"load only", `{"loadSession":true}`, false},
		{"no session capabilities", `{}`, false},
		{"resume null", `{"sessionCapabilities":{"resume":null}}`, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var caps AcpAgentCapabilities
			if err := json.Unmarshal([]byte(tc.json), &caps); err != nil {
				t.Fatalf("unmarshal capabilities: %v", err)
			}
			if got := caps.SupportsResume(); got != tc.want {
				t.Errorf("SupportsResume() = %v, want %v", got, tc.want)
			}
		})
	}
}

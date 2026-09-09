package acp

import (
	"strings"
	"testing"
	"unicode/utf8"
)

// TestBoundDetail_UnderWindowUnchanged verifies text within the head+tail
// window passes through untouched with no omitted bytes.
func TestBoundDetail_UnderWindowUnchanged(t *testing.T) {
	text := strings.Repeat("a", detailHeadBytes+detailTailBytes)
	got, omitted := boundDetail(text)
	if got != text {
		t.Error("expected unchanged detail within the window")
	}
	if omitted != 0 {
		t.Errorf("expected 0 omitted bytes, got %d", omitted)
	}
}

// TestBoundDetail_OverWindowKeepsHeadTailAndCount verifies an oversized
// transcript is clipped to a head+tail window with a marker carrying the exact
// omitted byte count.
func TestBoundDetail_OverWindowKeepsHeadTailAndCount(t *testing.T) {
	text := strings.Repeat("a", detailHeadBytes+detailTailBytes+10_000)
	got, omitted := boundDetail(text)
	if !strings.Contains(got, "bytes omitted") {
		t.Fatalf("expected an omitted-byte marker in %q", got)
	}
	if omitted != int64(10_000) {
		t.Errorf("expected 10000 omitted bytes, got %d", omitted)
	}
	if len(got) > detailHeadBytes+detailTailBytes+64 {
		t.Errorf("expected clipped detail near the window size, got %d bytes", len(got))
	}
	if !strings.HasPrefix(got, strings.Repeat("a", detailHeadBytes)) {
		t.Errorf("expected head of the transcript, got %q", got)
	}
	if !strings.HasSuffix(got, strings.Repeat("a", detailTailBytes)) {
		t.Errorf("expected tail of the transcript, got %q", got)
	}
}

// TestBoundDetail_RuneSafe verifies clipping never splits a multi-byte rune.
func TestBoundDetail_RuneSafe(t *testing.T) {
	text := strings.Repeat("é", detailHeadBytes+detailTailBytes+1000) // 2 bytes per rune
	got, _ := boundDetail(text)
	if !utf8.ValidString(got) {
		t.Fatal("boundDetail produced invalid UTF-8")
	}
	if strings.Contains(got, "\uFFFD") {
		t.Error("boundDetail produced a replacement rune")
	}
}

// TestBoundDetail_OmittedCountMatchesRuneCuts verifies the omitted count
// matches the actual head/tail retained after rune-aligned cuts.
func TestBoundDetail_OmittedCountMatchesRuneCuts(t *testing.T) {
	text := strings.Repeat("é", detailHeadBytes+detailTailBytes+500)
	got, omitted := boundDetail(text)
	headEnd := strings.Index(got, "\n…")
	if headEnd < 0 {
		t.Fatal("expected marker")
	}
	tailStart := headEnd + strings.Index(got[headEnd:], "…\n") + len("…\n")
	retained := headEnd + (len(got) - tailStart)
	expectedOmitted := int64(len(text) - retained)
	if omitted != expectedOmitted {
		t.Errorf("expected omitted %d, got %d", expectedOmitted, omitted)
	}
}

// TestHandleSessionUpdate_TerminalRelay_CapsDetailAndFlagsTruncated verifies
// the end-to-end terminal relay: the full transcript is streamed live via
// env.output (nothing silently dropped) while the final activity detail carries
// a bounded head+tail window with truncated=true and an omitted-byte marker.
func TestHandleSessionUpdate_TerminalRelay_CapsDetailAndFlagsTruncated(t *testing.T) {
	sink := &mockEventSink{}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(map[string]interface{}{
		"update": map[string]interface{}{
			"sessionUpdate": "tool_call",
			"toolCallId":    "tc-big",
			"kind":          "execute",
			"title":         "dump",
			"_meta": map[string]interface{}{
				"terminal_info": map[string]interface{}{"terminal_id": "term-big"},
			},
		},
	})

	chunk := strings.Repeat("y", detailHeadBytes+detailTailBytes+500)
	h.handleSessionUpdate(terminalRelayUpdate("in_progress", "tc-big", "term-big", chunk, nil, false))
	h.handleSessionUpdate(terminalRelayUpdate("completed", "tc-big", "term-big", "", nil, false))

	// The full transcript must reach the live relay.
	var relayed strings.Builder
	for _, out := range sink.outputs {
		if strings.HasPrefix(out.line, "[Tool]") {
			continue
		}
		relayed.WriteString(out.line)
	}
	if relayed.Len() < detailHeadBytes+detailTailBytes {
		t.Errorf("expected full transcript relayed live, got %d bytes", relayed.Len())
	}

	last := sink.activities[len(sink.activities)-1]
	if last.status != "completed" {
		t.Fatalf("expected completed activity, got %v", last.status)
	}
	if !last.detail.Truncated {
		t.Error("expected truncated=true in the final activity detail")
	}
	if len(last.detail.Output) > detailHeadBytes+detailTailBytes+64 {
		t.Errorf("expected bounded detail output, got %d bytes", len(last.detail.Output))
	}
	if !strings.Contains(last.detail.Output, "bytes omitted") {
		t.Error("expected an omitted-byte marker in detail.Output")
	}
}

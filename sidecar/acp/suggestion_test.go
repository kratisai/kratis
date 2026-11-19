package acp

import (
	"encoding/json"
	"fmt"
	"testing"
)

func suggestionUpdate(sessionUpdate, status string, rawInput string) map[string]interface{} {
	update := map[string]interface{}{
		"sessionUpdate": sessionUpdate,
		"kind":          "other",
		"title":         "suggest",
		"toolCallId":    "call-suggest-1",
		"status":        status,
		"locations":     []interface{}{},
	}
	if rawInput != "" {
		update["rawInput"] = json.RawMessage(rawInput)
	}
	return map[string]interface{}{"update": update}
}

func commandUpdate(sessionUpdate, status string) map[string]interface{} {
	return map[string]interface{}{"update": map[string]interface{}{
		"sessionUpdate": sessionUpdate,
		"kind":          "other",
		"title":         "goal_report",
		"toolCallId":    "call-other-1",
		"status":        status,
		"locations":     []interface{}{},
		"rawInput":      json.RawMessage(`{}`),
	}}
}

func TestParseSuggestion_SignatureVariants(t *testing.T) {
	full := parseSuggestion(map[string]any{
		"suggest": "Would you like to review the committed changes?",
		"actions": []any{
			map[string]any{
				"label":       "Review unpushed commits",
				"desc":        "ignored key",
				"description": "Review unpushed commits on current branch",
				"prompt":      "/review unpushed",
			},
			map[string]any{"label": "no prompt"},
		},
	})
	if full == nil {
		t.Fatal("expected suggestion from full payload")
	}
	if full.Question != "Would you like to review the committed changes?" {
		t.Errorf("unexpected question %q", full.Question)
	}
	if len(full.Actions) != 1 || full.Actions[0].Prompt != "/review unpushed" {
		t.Errorf("unexpected actions %+v", full.Actions)
	}

	questionOnly := parseSuggestion(map[string]any{"suggest": "Ship it?"})
	if questionOnly == nil || questionOnly.Question != "Ship it?" || len(questionOnly.Actions) != 0 {
		t.Errorf("unexpected question-only parse %+v", questionOnly)
	}

	actionsOnly := parseSuggestion(map[string]any{
		"actions": []any{map[string]any{"prompt": "/review unpushed"}},
	})
	if actionsOnly == nil || actionsOnly.Question != "The agent suggested follow-up actions." {
		t.Errorf("unexpected actions-only parse %+v", actionsOnly)
	}

	if parseSuggestion(map[string]any{"suggest": "   "}) != nil {
		t.Error("blank question without actions is not a suggestion")
	}
	if parseSuggestion(map[string]any{}) != nil {
		t.Error("empty rawInput is not a suggestion")
	}
}

func TestSuggestionFormSchema(t *testing.T) {
	schema := suggestionFormSchema(&Suggestion{
		Question: "Ship it?",
		Actions: []SuggestedAction{
			{Label: "Review unpushed commits", Description: "Review unpushed commits on current branch", Prompt: "/review unpushed"},
			{Prompt: "/push"},
		},
	})
	if schema[SuggestionFormMarker] != true {
		t.Error("expected the x-kratis-suggestion contract marker on the form root")
	}
	followUp, ok := schema["properties"].(map[string]any)["followUp"]
	if !ok {
		t.Fatal("expected followUp property")
	}
	followUpMap := followUp.(map[string]any)
	options := followUpMap["oneOf"].([]map[string]any)
	if len(options) != 2 {
		t.Fatalf("expected 2 options, got %d", len(options))
	}
	if options[0]["const"] != "/review unpushed" || options[0]["title"] != "Review unpushed commits" {
		t.Errorf("unexpected first option %+v", options[0])
	}
	if options[1]["const"] != "/push" || options[1]["title"] != nil {
		t.Errorf("unexpected unlabeled option %+v", options[1])
	}
	if _, ok := schema["properties"].(map[string]any)["direction"]; !ok {
		t.Error("expected direction property")
	}

	noActions := suggestionFormSchema(&Suggestion{Question: "Ship it?"})
	props := noActions["properties"].(map[string]any)
	if _, ok := props["followUp"]; ok {
		t.Error("followUp property must not exist without actions")
	}
}

func handleSuggestionSessionUpdate(t *testing.T, h *Handler, sessionUpdate, status, rawInput string) {
	t.Helper()
	h.handleSessionUpdate(suggestionUpdate(sessionUpdate, status, rawInput))
}

func handleSuggestionTerminalUpdate(t *testing.T, h *Handler, rawInput, text string) {
	t.Helper()
	update := suggestionUpdate("tool_call_update", "completed", rawInput)
	update["update"].(map[string]interface{})["content"] = []interface{}{
		map[string]interface{}{
			"type":    "content",
			"content": map[string]interface{}{"type": "text", "text": text},
		},
	}
	h.handleSessionUpdate(update)
}

func TestSuggestionToolCall_TranslatesToQuestion(t *testing.T) {
	sink := &mockEventSink{cancelChan: make(chan struct{})}
	h := NewHandler(sink, nil, "")

	// Pending shell carries no signature: no activity, no terminal noise.
	handleSuggestionSessionUpdate(t, h, "tool_call", "pending", "")
	if len(sink.activities) != 0 || len(sink.outputs) != 0 {
		t.Fatalf("pending shell must stay silent, got %d activities %d outputs: %+v", len(sink.activities), len(sink.outputs), sink.outputs)
	}

	// in_progress delivers the payload: question, [Suggestion] line, no command row.
	handleSuggestionSessionUpdate(t, h, "tool_call_update", "in_progress",
		`{"suggest":"Would you like to review the committed changes?","actions":[{"label":"Review unpushed commits","description":"Review unpushed commits on current branch","prompt":"/review unpushed"}]}`)
	if len(sink.suggestions) != 1 {
		t.Fatalf("expected one suggestion delivery, got %d", len(sink.suggestions))
	}
	delivered := sink.suggestions[0]
	if delivered.actionID != "call-suggest-1" {
		t.Errorf("unexpected actionID %q", delivered.actionID)
	}
	if delivered.message != "Would you like to review the committed changes?" {
		t.Errorf("unexpected message %q", delivered.message)
	}
	if _, ok := delivered.form["properties"].(map[string]any)["followUp"]; !ok {
		t.Error("expected followUp in form")
	}
	if len(sink.activities) != 0 {
		t.Errorf("command shell must stay suppressed, got %d activities", len(sink.activities))
	}
	found := false
	for _, o := range sink.outputs {
		if o.line == "[Suggestion] Would you like to review the committed changes?" && o.stream == "stdout" {
			found = true
		}
		if len(o.line) >= 6 && o.line[:6] == "[Tool]" {
			t.Errorf("no [Tool] line expected for suggestion updates, got %q", o.line)
		}
	}
	if !found {
		t.Error("expected [Suggestion] terminal line")
	}

	// Duplicate in_progress update: still one delivery.
	handleSuggestionSessionUpdate(t, h, "tool_call_update", "in_progress",
		`{"suggest":"Would you like to review the committed changes?","actions":[{"prompt":"/review unpushed"}]}`)
	if len(sink.suggestions) != 1 {
		t.Fatalf("latch must prevent duplicate delivery, got %d", len(sink.suggestions))
	}

	// Terminal update: output reaches the terminal relay, no activity row.
	handleSuggestionTerminalUpdate(t, h,
		`{"suggest":"Would you like to review the committed changes?"}`,
		"User dismissed the suggestion.")
	if len(sink.activities) != 0 {
		t.Errorf("terminal suggestion must not emit an activity row, got %d", len(sink.activities))
	}
	sawOutput := false
	for _, o := range sink.outputs {
		if o.line == "User dismissed the suggestion." {
			sawOutput = true
		}
	}
	if !sawOutput {
		t.Errorf("expected harness terminal output, got %+v", sink.outputs)
	}
}

func TestSuggestionToolCall_TerminalWithoutSignatureEmitsPlainRow(t *testing.T) {
	sink := &mockEventSink{cancelChan: make(chan struct{})}
	h := NewHandler(sink, nil, "")

	handleSuggestionSessionUpdate(t, h, "tool_call", "pending", "")
	handleSuggestionSessionUpdate(t, h, "tool_call_update", "completed", `{}`)
	if len(sink.suggestions) != 0 {
		t.Error("no question expected without a signature")
	}
	if len(sink.activities) != 1 {
		t.Fatalf("expected the deferred shell to emit at terminal status, got %d", len(sink.activities))
	}
	act := sink.activities[0]
	if act.activityType != ActivityTypeCommand || act.description != "suggest" || act.actionID != "call-suggest-1" {
		t.Errorf("unexpected shell row %+v", act)
	}
}

func TestSuggestionGate_LeavesOtherToolCallsAlone(t *testing.T) {
	sink := &mockEventSink{cancelChan: make(chan struct{})}
	h := NewHandler(sink, nil, "")

	h.handleSessionUpdate(commandUpdate("tool_call", "pending"))
	h.handleSessionUpdate(commandUpdate("tool_call_update", "in_progress"))
	if len(sink.suggestions) != 0 {
		t.Error("goal_report must not be translated")
	}
	if len(sink.activities) != 2 {
		t.Errorf("expected normal command rows, got %d", len(sink.activities))
	}
}

func TestSuggestionToolCall_RelayExhaustiveStatuses(t *testing.T) {
	for _, tc := range []struct {
		sessionUpdate string
		status        string
		signature     string
		wantQuestions int
	}{
		{"tool_call", "pending", "", 0},
		{"tool_call", "pending", `{"suggest":"Ship it?"}`, 1},
		{"tool_call", "failed", "", 0},
	} {
		t.Run(fmt.Sprintf("%s_%s", tc.sessionUpdate, tc.status), func(t *testing.T) {
			sink := &mockEventSink{cancelChan: make(chan struct{})}
			h := NewHandler(sink, nil, "")
			handleSuggestionSessionUpdate(t, h, tc.sessionUpdate, tc.status, tc.signature)
			if len(sink.suggestions) != tc.wantQuestions {
				t.Errorf("expected %d questions, got %d", tc.wantQuestions, len(sink.suggestions))
			}
		})
	}
}

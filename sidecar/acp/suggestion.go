package acp

import "strings"

// Harness "suggestion" tool calls (Kilo's `suggest`) offer one-click follow-up
// prompts to a native harness client. ACP has no response channel for them:
// the agent's prompt turn only advances when the next user turn arrives, so
// Kratis translates the call into an ordinary HITL question. The control plane
// steers the resolution (chosen follow-up, alternative direction, or a
// dismissal notice) back as a new turn; the blocked harness prompt then
// resolves by itself and the tool_call lifecycle closes.

// SuggestedAction is one actionable follow-up chip in a suggestion payload.
type SuggestedAction struct {
	Label       string
	Description string
	Prompt      string
}

// Suggestion is the recognized payload of a suggestion tool call.
type Suggestion struct {
	Question string
	Actions  []SuggestedAction
}

// SuggestionRequest is the question forwarded to the control plane. ActionID
// is the ACP toolCallId, so resolution and any later tool_call lifecycle share
// one correlation id.
type SuggestionRequest struct {
	ActionID string
	Message  string
	Form     map[string]any
}

// isSuggestionSuspect gates kind-"other" tool calls titled "suggest" through
// the suggestion translation before any command activity is emitted.
func isSuggestionSuspect(kind, title string) bool {
	return kind == string(KindOther) && strings.EqualFold(strings.TrimSpace(title), "suggest")
}

// parseSuggestion recognizes the wire signature of a suggestion: a non-empty
// `suggest` string (the question) and/or `actions` entries with a non-empty
// `prompt`. The detection is payload-shaped, not title-bound. nil when the
// accumulated rawInput carries neither (Kilo announces the shell with an empty
// rawInput and delivers the payload with the in_progress update).
func parseSuggestion(rawInput map[string]any) *Suggestion {
	if len(rawInput) == 0 {
		return nil
	}
	question, _ := rawInput["suggest"].(string)
	if strings.TrimSpace(question) == "" {
		question = ""
	}
	var actions []SuggestedAction
	if entries, ok := rawInput["actions"].([]any); ok {
		for _, entry := range entries {
			m, ok := entry.(map[string]any)
			if !ok {
				continue
			}
			prompt, _ := m["prompt"].(string)
			if strings.TrimSpace(prompt) == "" {
				continue
			}
			label, _ := m["label"].(string)
			description, _ := m["description"].(string)
			actions = append(actions, SuggestedAction{
				Label:       label,
				Description: description,
				Prompt:      prompt,
			})
		}
	}
	if question == "" && len(actions) == 0 {
		return nil
	}
	if question == "" {
		question = "The agent suggested follow-up actions."
	}
	return &Suggestion{Question: question, Actions: actions}
}

// SuggestionFormMarker flags the sidecar-authored question form so the control
// plane applies the suggestion steering contract without hijacking generic
// harness elicitations that merely define their own followUp/direction keys.
const SuggestionFormMarker = "x-kratis-suggestion"

// suggestionFormSchema renders the elicitation form: a selectable follow-up
// whose submitted value is the exact prompt to dispatch, plus a free-text
// direction for steering an alternative instead of the suggestion.
func suggestionFormSchema(s *Suggestion) map[string]any {
	properties := map[string]any{}
	if len(s.Actions) > 0 {
		options := make([]map[string]any, 0, len(s.Actions))
		for _, action := range s.Actions {
			option := map[string]any{"const": action.Prompt}
			if action.Label != "" {
				option["title"] = action.Label
			}
			if action.Description != "" {
				option["description"] = action.Description
			}
			options = append(options, option)
		}
		properties["followUp"] = map[string]any{
			"type":        "string",
			"title":       "Apply a suggested follow-up",
			"description": "Sent to the agent as the next turn, exactly as written",
			"oneOf":       options,
		}
	}
	properties["direction"] = map[string]any{
		"type":        "string",
		"title":       "Or give an alternative direction",
		"description": "Steers the agent along your own instruction instead of the suggestion",
	}
	return map[string]any{
		"type":               "object",
		"title":              "Agent suggestion",
		SuggestionFormMarker: true,
		"properties":         properties,
	}
}

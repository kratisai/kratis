package runner

// CompletionInfo is the terminal env.complete payload the supervisor reports:
// the exit code plus, for abnormal terminal paths, the reason as reported by
// the agent (its own error message on a fatal abort, or the exit detail on an
// unexpected process death).
//
// Terminated marks completions where the supervisor deliberately stopped the
// agent (a control-plane env.terminate or an internal teardown). Such stops are
// expected and must not be surfaced as agent failures, even when the process
// exit code is non-zero.
type CompletionInfo struct {
	ExitCode   int
	Reason     string
	Terminated bool
}

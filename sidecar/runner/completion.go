package runner

// CompletionInfo is the terminal env.complete payload the supervisor reports:
// the exit code plus, for abnormal terminal paths, the reason as reported by
// the agent (its own error message on a fatal abort, or the exit detail on an
// unexpected process death).
type CompletionInfo struct {
	ExitCode int
	Reason   string
}

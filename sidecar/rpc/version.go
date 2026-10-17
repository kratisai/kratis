package rpc

import (
	"runtime/debug"
	"strings"
	"time"
)

const (
	shortCommitLength = 12
	unknownPart       = "unknown"
	dirtySuffix       = "-dirty"
	buildTimeLayout   = time.RFC3339
	stampSeparator    = "+"
)

// buildCommit and buildTime are stamped at build time. buildCommit is optional (a build in a git
// checkout embeds the revision itself), buildTime is not, because Go embeds no build timestamp:
//
//	go build -ldflags "-X kratis-connector/rpc.buildCommit=<sha> -X kratis-connector/rpc.buildTime=<iso8601>" .
//
// Every documented build (sidecar/build.sh, the control-plane image) stamps them, so a woken
// sandbox can be matched to the exact binary that was copied into it.
var (
	buildCommit string
	buildTime   string
)

// ConnectorVersion identifies this build as "<sha12>+<build-time>[-dirty]". The commit is shortened
// to 12 characters, -dirty marks a modified tree, and any missing part is reported as "unknown"
// (for example a build outside a git checkout with no stamped commit reports "unknown+<build-time>").
func ConnectorVersion() string {
	info, ok := debug.ReadBuildInfo()
	return formatConnectorVersion(buildCommit, buildTime, info, ok)
}

func formatConnectorVersion(stampedCommit, stampedTime string, info *debug.BuildInfo, haveInfo bool) string {
	commit := strings.TrimSpace(stampedCommit)
	modified := false
	if commit == "" && haveInfo && info != nil {
		for _, setting := range info.Settings {
			switch setting.Key {
			case "vcs.revision":
				commit = setting.Value
			case "vcs.modified":
				modified = setting.Value == "true"
			}
		}
	}
	if commit == "" {
		commit = unknownPart
	} else if len(commit) > shortCommitLength {
		commit = commit[:shortCommitLength]
	}

	version := commit + stampSeparator + normaliseBuildTime(stampedTime)
	if modified {
		version += dirtySuffix
	}
	return version
}

// normaliseBuildTime renders the stamp as RFC3339 UTC, and "unknown" when the build stamped none.
func normaliseBuildTime(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return unknownPart
	}
	parsed, err := time.Parse(buildTimeLayout, value)
	if err != nil {
		return value
	}
	return parsed.UTC().Format(buildTimeLayout)
}

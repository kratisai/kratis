#!/bin/sh
# Builds kratis-connector with the build time stamped so ConnectorVersion() identifies the exact
# binary that sandbox provisioning copies into a sandbox. Pass an output path (default
# kratis-connector).
set -eu

output=${1:-kratis-connector}
build_time=$(date -u +%Y-%m-%dT%H:%M:%SZ)

exec go build -ldflags "-X kratis-connector/rpc.buildTime=${build_time}" -o "$output" .

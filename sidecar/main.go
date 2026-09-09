package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"kratis-connector/rpc"
)

// AgentConfig holds the parsed configuration for the agent.
type AgentConfig struct {
	Mode        string
	Token       string
	ServerURL   string
	ContainerID string
	Workspace   string
	LogFile     string
	Debug       bool
}

// ParseConfig parses command-line flags and environment variables into an AgentConfig.
// It returns an error if required configuration is missing.
func ParseConfig(args []string) (*AgentConfig, error) {
	fs := flag.NewFlagSet("kratis-connector", flag.ContinueOnError)
	modeFlag := fs.String("mode", "sidecar", "Agent mode (daemon or sidecar)")
	tokenFlag := fs.String("token", "", "Authentication token")
	serverURLFlag := fs.String("server-url", "", "Control plane WebSocket URL")
	containerIDFlag := fs.String("container-id", "", "Container ID (only for sidecar mode)")
	logFileFlag := fs.String("log-file", "", "Path to log file (if set, all log output is written to this file)")
	debugFlag := fs.Bool("debug", false, "Relay sidecar-internal diagnostics into env.output")

	if err := fs.Parse(args); err != nil {
		return nil, err
	}

	cfg := &AgentConfig{
		Mode:        *modeFlag,
		Token:       *tokenFlag,
		ServerURL:   *serverURLFlag,
		ContainerID: *containerIDFlag,
		LogFile:     *logFileFlag,
		Debug:       *debugFlag,
	}

	if cfg.Token == "" {
		cfg.Token = os.Getenv("KRATIS_TOKEN")
	}
	if cfg.ServerURL == "" {
		cfg.ServerURL = os.Getenv("KRATIS_SERVER_URL")
	}
	if cfg.ContainerID == "" {
		cfg.ContainerID = os.Getenv("KRATIS_CONTAINER_ID")
	}

	workspace, err := os.Getwd()
	if err != nil {
		workspace = "."
	}
	cfg.Workspace = workspace

	if cfg.Token == "" {
		return nil, fmt.Errorf("token is required (provide via --token flag or KRATIS_TOKEN env var)")
	}
	if cfg.ServerURL == "" {
		return nil, fmt.Errorf("server-url is required (provide via --server-url flag or KRATIS_SERVER_URL env var)")
	}

	return cfg, nil
}

func main() {
	cfg, err := ParseConfig(os.Args[1:])
	if err != nil {
		log.Fatalf("Error: %v", err)
	}

	// If --log-file is specified, redirect all log output to the file.
	// This is essential when running in detached mode (docker exec -d) where
	// stdout/stderr is not captured by Docker's logging driver.
	if cfg.LogFile != "" {
		f, err := os.OpenFile(cfg.LogFile, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600)
		if err != nil {
			// Can't use log.Printf here since we're trying to set up logging
			fmt.Fprintf(os.Stderr, "Warning: failed to open log file %s: %v\n", cfg.LogFile, err)
		} else {
			log.SetOutput(f)
			defer func() {
				if err := f.Close(); err != nil {
					log.Printf("Failed to close log file: %v", err)
				}
			}()
		}
	}

	log.Printf("Starting Kratis Connector (kratis-connector) in mode: %s", cfg.Mode)
	log.Printf("Server URL: %s", cfg.ServerURL) //nolint:gosec // G706: config value, not attacker-controlled
	log.Printf("Workspace directory: %s", cfg.Workspace)

	client := rpc.NewClient(cfg.ServerURL, cfg.Token, cfg.ContainerID, cfg.Workspace)
	client.SetDebug(cfg.Debug)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	client.Start(ctx)
	log.Println("Kratis Connector stopped.")
}

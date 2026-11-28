package rpc

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Publish spool. An agent harness working in the sandbox has no direct access to the
// environment WebSocket, so it publishes user-facing results by dropping files into a spool
// directory that the sidecar owns and polls. The spool is .kratis/publish inside the git
// workspace: some harnesses cannot read or write outside the workspace, and .kratis is
// git-excluded, so published files never reach the execution diff. Two publication kinds are
// supported:
//
//   - preview: a JSON manifest (preview-*.json) describing a sandbox-local port serving an
//     application. The sidecar sends env.preview; the control plane owns the address
//     translation and returns the user-openable URL.
//   - attachment: a file plus a *.meta.json manifest (images and markdown only). The sidecar
//     base64-encodes the content and sends env.attachment; the control plane stores it and
//     returns an inline view URL.
//
// Processed manifests are moved to a done/ subdirectory rather than deleted, so a sandbox
// suspend/resume cycle does not replay them.

const (
	publishPollInterval = 500 * time.Millisecond

	// maxAttachmentBytes is the decoded content cap. Base64 inflates content by 4/3 and the JSON
	// envelope adds overhead, so 6 MB decodes to ~8 MB base64, which stays comfortably under the
	// 12 MB outbound frame limit (and the control plane's inbound limit).
	maxAttachmentBytes = 6 * 1024 * 1024
)

// PreviewManifest is the JSON shape of a preview-*.json spool entry.
type PreviewManifest struct {
	ExecutionID string `json:"executionId,omitempty"`
	Port        int    `json:"port"`
	Label       string `json:"label"`
	Path        string `json:"path,omitempty"`
	Description string `json:"description,omitempty"`
}

// AttachmentMeta is the JSON shape of an attachment-*.meta.json manifest. The payload sits
// next to it as attachment-<id>.<ext> (the manifest path with .meta.json stripped).
type AttachmentMeta struct {
	ExecutionID  string `json:"executionId,omitempty"`
	AttachmentID string `json:"attachmentId,omitempty"`
	Kind         string `json:"kind"`
	Title        string `json:"title,omitempty"`
	ContentType  string `json:"contentType,omitempty"`
	Summary      string `json:"summary,omitempty"`
}

// PublishResult reports the outcome of one spool entry so the agent-facing log line can
// surface the control-plane-returned URL or the rejection reason.
type PublishResult struct {
	Kind       string // "preview" or "attachment"
	Source     string // spool manifest path
	PreviewURL string
	ViewURL    string
	Error      string
}

// PublishPoller watches the publish spool directory and forwards entries to the control
// plane. It mirrors AgentLogTailer: files may appear at any time, each is handled once,
// and Stop() performs a final drain pass.
type PublishPoller struct {
	client   *Client
	spoolDir string
	interval time.Duration
	stop     chan struct{}
	done     chan struct{}
	once     sync.Once
	onResult func(PublishResult)
}

// publishSpoolDir returns the spool directory. It lives at .kratis/publish inside the
// git workspace so harnesses restricted to the workspace can write it; .kratis is
// git-excluded, so published files never reach the execution diff.
func (c *Client) publishSpoolDir() string {
	return filepath.Join(c.resolveWorkspace(), ".kratis", "publish")
}

// StartPublishPolling begins polling the publish spool. It returns nil when the spool cannot
// be created. Safe to call on a nil client.
func (c *Client) StartPublishPolling(onResult func(PublishResult)) *PublishPoller {
	if c == nil {
		return nil
	}
	spool := c.publishSpoolDir()
	if err := os.MkdirAll(filepath.Join(spool, "done"), 0o750); err != nil {
		log.Printf("[PUBLISH] cannot create spool %s: %v", spool, err)
		return nil
	}
	p := &PublishPoller{
		client:   c,
		spoolDir: spool,
		interval: publishPollInterval,
		stop:     make(chan struct{}),
		done:     make(chan struct{}),
		onResult: onResult,
	}
	go p.loop()
	return p
}

// Stop ends polling after a final drain pass. Safe to call on a nil poller.
func (p *PublishPoller) Stop() {
	if p == nil {
		return
	}
	p.once.Do(func() { close(p.stop) })
	<-p.done
}

func (p *PublishPoller) loop() {
	defer close(p.done)
	ticker := time.NewTicker(p.interval)
	defer ticker.Stop()
	for {
		select {
		case <-p.stop:
			p.drain()
			return
		case <-ticker.C:
			p.drain()
		}
	}
}

// drain scans the spool once and processes every manifest it finds.
func (p *PublishPoller) drain() {
	entries, err := os.ReadDir(p.spoolDir)
	if err != nil {
		return
	}
	for _, entry := range entries {
		if entry.IsDir() || !isPublishManifest(entry.Name()) {
			continue
		}
		p.process(filepath.Join(p.spoolDir, entry.Name()))
	}
}

// isPublishManifest reports whether a spool file is a manifest rather than an attachment
// payload. Only preview-*.json and attachment-*.meta.json are manifests. An attachment
// payload may itself end in .json (e.g. attachment-report.json); treating it as a manifest
// would consume it before its .meta.json arrives, so publishing a JSON payload would always
// fail with "attachment payload not found".
func isPublishManifest(name string) bool {
	if strings.HasSuffix(name, ".meta.json") {
		return strings.HasPrefix(name, "attachment-")
	}
	if strings.HasSuffix(name, ".json") {
		return strings.HasPrefix(name, "preview-")
	}
	return false
}

func (p *PublishPoller) process(path string) {
	result := p.handle(path)
	// Move the processed manifest into done/ so a suspend/resume replay does not
	// re-publish. The attachment payload is removed after a successful send.
	_ = os.Rename(path, filepath.Join(p.spoolDir, "done", filepath.Base(path)))
	if p.onResult != nil {
		p.onResult(result)
	}
}

func (p *PublishPoller) handle(path string) PublishResult {
	name := filepath.Base(path)
	switch {
	case strings.HasPrefix(name, "preview-"):
		return p.handlePreview(path)
	case strings.HasPrefix(name, "attachment-"):
		return p.handleAttachment(path)
	default:
		return PublishResult{Source: path, Error: "unknown publish entry prefix"}
	}
}

func (p *PublishPoller) handlePreview(path string) PublishResult {
	raw, err := os.ReadFile(path) //nolint:gosec // G304: path is a publish spool entry the agent dropped for the sidecar to read by design
	if err != nil {
		return PublishResult{Kind: "preview", Source: path, Error: err.Error()}
	}
	var manifest PreviewManifest
	if err := json.Unmarshal(raw, &manifest); err != nil {
		return PublishResult{Kind: "preview", Source: path, Error: "invalid preview manifest: " + err.Error()}
	}
	if manifest.Port < 1 || manifest.Port > 65535 {
		return PublishResult{Kind: "preview", Source: path, Error: fmt.Sprintf("preview port %d out of range", manifest.Port)}
	}
	if manifest.Label == "" {
		manifest.Label = fmt.Sprintf("port-%d", manifest.Port)
	}
	execID := manifest.ExecutionID
	if execID == "" {
		execID = p.client.currentExecutionIDString()
	}
	if execID == "" {
		return PublishResult{Kind: "preview", Source: path, Error: "no active execution to attribute the preview to"}
	}
	resp, err := p.client.sendRequest("env.preview", PreviewParams{
		ExecutionID: execID,
		Port:        manifest.Port,
		Label:       manifest.Label,
		Path:        manifest.Path,
		Description: manifest.Description,
	})
	if err != nil {
		return PublishResult{Kind: "preview", Source: path, Error: err.Error()}
	}
	if resp.Error != nil {
		return PublishResult{Kind: "preview", Source: path, Error: fmt.Sprintf("control plane rejected preview: %s", resp.Error.Message)}
	}
	var result PreviewResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		return PublishResult{Kind: "preview", Source: path, Error: "invalid preview result: " + err.Error()}
	}
	return PublishResult{Kind: "preview", Source: path, PreviewURL: result.PreviewURL}
}

func (p *PublishPoller) handleAttachment(path string) PublishResult {
	raw, err := os.ReadFile(path) //nolint:gosec // G304: path is a publish spool entry the agent dropped for the sidecar to read by design
	if err != nil {
		return PublishResult{Kind: "attachment", Source: path, Error: err.Error()}
	}
	var meta AttachmentMeta
	if err := json.Unmarshal(raw, &meta); err != nil {
		return PublishResult{Kind: "attachment", Source: path, Error: "invalid attachment meta: " + err.Error()}
	}
	payloadPath := strings.TrimSuffix(path, ".meta.json")
	if payloadPath == path {
		return PublishResult{Kind: "attachment", Source: path, Error: "attachment manifest must end in .meta.json"}
	}
	content, err := os.ReadFile(payloadPath) //nolint:gosec // G304: payload sits next to the spool manifest the agent dropped for the sidecar to read by design
	if err != nil {
		return PublishResult{Kind: "attachment", Source: path, Error: "attachment payload not found: " + payloadPath}
	}
	if len(content) > maxAttachmentBytes {
		return PublishResult{Kind: "attachment", Source: path, Error: fmt.Sprintf("attachment exceeds %d bytes", maxAttachmentBytes)}
	}
	kind := AttachmentKind(meta.Kind)
	if kind != AttachmentKindImage && kind != AttachmentKindMarkdown {
		return PublishResult{Kind: "attachment", Source: path, Error: "unsupported attachment kind (only image and markdown)"}
	}
	contentType := meta.ContentType
	if contentType == "" {
		contentType = defaultAttachmentContentType(kind, payloadPath)
	}
	attachmentID := meta.AttachmentID
	if attachmentID == "" {
		attachmentID = strings.TrimSuffix(filepath.Base(payloadPath), filepath.Ext(payloadPath))
	}
	execID := meta.ExecutionID
	if execID == "" {
		execID = p.client.currentExecutionIDString()
	}
	if execID == "" {
		return PublishResult{Kind: "attachment", Source: path, Error: "no active execution to attribute the attachment to"}
	}
	resp, err := p.client.sendRequest("env.attachment", AttachmentParams{
		ExecutionID:   execID,
		AttachmentID:  attachmentID,
		Kind:          kind,
		Title:         meta.Title,
		ContentType:   contentType,
		ContentBase64: base64.StdEncoding.EncodeToString(content),
		Summary:       meta.Summary,
	})
	if err != nil {
		return PublishResult{Kind: "attachment", Source: path, Error: err.Error()}
	}
	if resp.Error != nil {
		return PublishResult{Kind: "attachment", Source: path, Error: fmt.Sprintf("control plane rejected attachment: %s", resp.Error.Message)}
	}
	var result AttachmentResult
	if err := json.Unmarshal(resp.Result, &result); err != nil {
		return PublishResult{Kind: "attachment", Source: path, Error: "invalid attachment result: " + err.Error()}
	}
	// Remove the payload once the control plane accepted it; the manifest moves to done/.
	_ = os.Remove(payloadPath)
	return PublishResult{Kind: "attachment", Source: path, ViewURL: result.ViewURL}
}

// defaultAttachmentContentType derives a MIME type from the publish kind and file extension.
func defaultAttachmentContentType(kind AttachmentKind, payloadPath string) string {
	if kind == AttachmentKindMarkdown {
		return "text/markdown"
	}
	switch strings.ToLower(filepath.Ext(payloadPath)) {
	case ".png":
		return "image/png"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".webp":
		return "image/webp"
	case ".gif":
		return "image/gif"
	case ".svg":
		return "image/svg+xml"
	default:
		return "application/octet-stream"
	}
}

// currentExecutionIDString reads the current execution id under the client lock.
func (c *Client) currentExecutionIDString() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.currentExecutionID
}

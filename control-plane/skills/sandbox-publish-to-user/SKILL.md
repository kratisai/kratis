---
name: sandbox-publish-to-user
description: Publish sandbox results to the user through the Kratis control plane. Use when an agent has a user-facing result to expose, such as a running application preview URL, a screenshot, a chart, or a markdown report. Covers the preview spool manifest, the image and markdown attachment upload, and what the user sees in the task activity log.
---

# Publish sandbox results to the user

The user cannot reach the sandbox network. To show them a result, publish it through the control
plane: the control plane owns the address translation, stores the bytes, and writes a thumbnail
or link into the task activity log. Two publication kinds, both via the publish spool the sidecar
polls.

The spool is `.kratis/publish` inside the workspace. It is inside the workspace because some
harnesses cannot read or write outside it, and `.kratis/` is git-excluded, so published files
never reach the execution diff. The sidecar polls it and forwards each entry to the control
plane as `env.preview` or `env.attachment`. Processed entries move to `.kratis/publish/done/`.

## 1. Publish a running application preview

When `sandbox-run-application` has a service up on a sandbox-local port, drop a manifest:

```
mkdir -p .kratis/publish
cat > .kratis/publish/preview-1.json <<'EOF'
{"port": 3000, "label": "Web app", "path": "/"}
EOF
```

Fields: `port` (required, 1-65535), `label` (required, shown in the activity log), `path`
(optional, appended to the URL), `description` (optional), `executionId` (optional; the sidecar
fills the current execution).

The control plane returns and logs a URL on its own origin, e.g.
`/preview/<environmentId>/3000/`. The user opens that URL; the control plane proxies it to the
sandbox-local port. Never hand the user a raw sandbox IP or port.

## 2. Publish an attachment (image or markdown)

Write the payload and a sidecar manifest. Only images and markdown are supported initially.

```
mkdir -p .kratis/publish
cp /tmp/screenshot.png .kratis/publish/attachment-shot-1.png
cat > .kratis/publish/attachment-shot-1.png.meta.json <<'EOF'
{"attachmentId": "shot-1", "kind": "image", "title": "Checkout screenshot", "contentType": "image/png"}
EOF
```

For a markdown report:

```
cat > .kratis/publish/attachment-report-1.md <<'EOF'
# Run report

Passed 12/12 checks.
EOF
cat > .kratis/publish/attachment-report-1.md.meta.json <<'EOF'
{"attachmentId": "report-1", "kind": "markdown", "title": "Run report", "contentType": "text/markdown"}
EOF
```

Fields: `attachmentId` (optional, defaults to the payload filename stem; must be unique), `kind`
(required, `image` or `markdown`), `title` (required, shown in the activity log), `contentType`
(required, one of image/png, image/jpeg, image/webp, image/gif, image/svg+xml, text/markdown),
`summary` (optional), `executionId` (optional).

The decoded file must be under 6 MB. The control plane stores the bytes, serves them inline, and
records an activity-log entry the web UI renders as an expandable thumbnail (image) or a rendered
preview (markdown).

## 3. What the user sees

Both kinds land in the task activity log:

- A preview entry shows the label and an "Open preview" link to the control-plane-origin URL.
- An attachment entry shows the title, an inline thumbnail for images, and an expand control that
  opens the full image or the rendered markdown.

## 4. Playwright screenshots

`sandbox-playwright-e2e` writes artifacts under `.kratis/test-results/` (git-ignored, so they never
enter the diff). To publish a screenshot, copy it into the spool as an image attachment
(section 2) so the user gets a thumbnail without opening the diff. Do not commit test artifacts.

## 5. Failures

- Entry not forwarded - check the sidecar log for `[PUBLISH] ... failed` and the manifest JSON.
- `unsupported attachment kind` - only `image` and `markdown` are supported.
- `attachment exceeds` - the decoded file is over 6 MB; resize the image or split the report.
- `no active execution` - the sidecar has no execution to attribute the publication to; publish
  while a task turn is active.

The full spool contract and the control-plane proxy/storage design are in
`references/publish-contract.md`.

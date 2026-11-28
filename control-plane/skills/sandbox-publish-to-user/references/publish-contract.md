# Publish spool contract

Reference for `sandbox-publish-to-user`. The agent writes files; the sidecar polls the spool,
validates, and forwards each entry to the control plane over the environment WebSocket. The
control plane owns the sandbox-to-user address translation and the storage.

## Spool location

`.kratis/publish` — inside the git workspace. It lives inside the workspace because some
harnesses cannot read or write outside it, and `.kratis/` is git-excluded, so nothing the agent
publishes appears in the execution diff. The sidecar creates the spool and a `done/` subdirectory
on startup.

## Polling and lifecycle

- The sidecar polls the spool every 500 ms while the environment WebSocket is connected.
- Each entry is handled once. The manifest is moved to `.kratis/publish/done/` after it is
  processed, so a sandbox suspend/resume cycle does not replay it.
- For attachments, the payload file is deleted after the control plane accepts it.

## Preview manifest

File: `preview-<anything>.json`

```json
{
  "executionId": "optional-uuid",
  "port": 3000,
  "label": "Web app",
  "path": "/health",
  "description": "optional note"
}
```

- `port` (required, 1-65535) and `label` (required) are validated by the sidecar before sending.
- `executionId` is optional; when omitted the sidecar fills the current execution. If there is no
  active execution the entry is skipped and logged.
- The sidecar sends `env.preview { executionId, port, label, path, description }`.
- The control plane records a `PUBLISH` activity with `previewUrl` and `previewLabel` in the
  detail, broadcasts it, and returns `{ type: "env_preview", status: "published", previewUrl }`.
- The preview URL is control-plane-relative: `/preview/<environmentId>/<port>/<path>`. The control
  plane proxies `/preview/<environmentId>/<port>/**` to the sandbox-local port, so the user only
  ever sees the control-plane origin.

## Attachment payload + manifest

Payload: `attachment-<name>.<ext>` (any file the agent produced).
Manifest: `attachment-<name>.<ext>.meta.json` (the payload path with `.meta.json` appended).
Only `attachment-*.meta.json` files are read as manifests, so a payload whose own name ends in
`.json` (for example `attachment-data.json`) is never mistaken for its manifest.

```json
{
  "executionId": "optional-uuid",
  "attachmentId": "shot-1",
  "kind": "image",
  "title": "Checkout screenshot",
  "contentType": "image/png",
  "summary": "optional one-liner"
}
```

- `kind` (required): `image` or `markdown`. Anything else is rejected by the sidecar before upload.
- `title` (required): shown in the activity log.
- `contentType` (required): `image/png`, `image/jpeg`, `image/webp`, `image/gif`,
  `image/svg+xml`, or `text/markdown`. The control plane rejects anything else.
- `attachmentId` (optional): defaults to the payload filename stem. Must be unique and a single
  path-safe token — letters, digits, `.`, `_`, `-`, starting with a letter or digit; `..` and path
  separators are rejected. It becomes part of the blob path and the view URL.
- `executionId` (optional): the sidecar fills the current execution when omitted.
- The sidecar reads the payload, base64-encodes it, and sends
  `env.attachment { executionId, attachmentId, kind, title, contentType, contentBase64, summary }`.
- Size: the decoded payload must be under 6 MB. Base64 inflates it by 4/3, so the encoded frame
  stays well under the 12 MB WebSocket frame limit.
- The control plane stores the bytes in blob storage under
  `attachments/<environmentId>/<attachmentId>`, writes a `.content-type` sidecar, records an
  `ATTACHMENT` activity whose detail carries the `attachment` metadata, broadcasts it, and returns
  `{ type: "env_attachment", status: "published", attachmentId, viewUrl }`.
- The view URL is `/attachments/<environmentId>/<attachmentId>`, served inline by the control
  plane. It is a capability URL: the environment id and attachment id are unguessable UUIDs, so
  the web UI can render the thumbnail with a plain `<img src>` without an auth header.

## Control-plane address translation

The user-facing URL is always control-plane-origin. The control plane resolves the sandbox-local
upstream itself: it discovers the runner container's address on `kratis-net-<environmentId>` and,
when the control plane runs in a container, joins that network before proxying. The sandbox
address never appears in the activity log or the URL the user opens.

## What the web UI renders

- Preview: an activity-log row with the label and an "Open preview" link.
- Attachment: an activity-log row with the title and an inline thumbnail for images; clicking
  expands to the full image, or fetches and renders the markdown.

## Supported types and the extension path

Only images and markdown ship initially. The control plane validates the kind and content type on
both ends (sidecar guard and control-plane guard), so an unsupported type is rejected with a clear
error rather than stored. New kinds are added by extending the `kind` enum and the content-type
allowlist in one place each.

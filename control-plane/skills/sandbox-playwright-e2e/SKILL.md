---
name: sandbox-playwright-e2e
description: Run real-browser end-to-end tests with Playwright in the Kratis sandbox and publish screenshots to the user. Use when a task needs browser testing, UI verification, a click-through of a web app, or a Playwright/E2E test run.
---

# Run Playwright E2E in the sandbox

The sandbox has no display. Run Chromium headless. The user cannot watch a live browser, so the
value is the evidence you publish: screenshots to the activity log and traces into the workspace.
Chromium works. WebKit and Firefox do not without extra system libraries.

## 1. Install Playwright

- Add it to the project as a dev dependency: `npm i -D @playwright/test`.
- Install the OS libraries once (needs sudo, runs apt): `sudo DEBIAN_FRONTEND=noninteractive npx playwright install --with-deps chromium`.
- Then install the browser into the user cache (do not keep the root cache):

```
npx playwright install chromium
```

`--with-deps` as root downloads browsers into `/root/.cache`, which the agent cannot use.
The plain command fills `/home/kratis/.cache/ms-playwright`.

- Use Chromium only. Skip WebKit and Firefox. They fail on missing GTK/GStreamer libraries.
- To list missing libraries before installing: `npx playwright install --dry-run chromium`.

## 2. Configure

- Use `@playwright/test`. Do not wire Playwright into Vitest or Jest.
- Copy `references/playwright.config.ts` as the starting config. It sets:
  - `trace: 'retain-on-failure'`, `screenshot: 'only-on-failure'`, `video: 'retain-on-failure'`.
  - `launchOptions.args: ['--no-sandbox','--disable-dev-shm-usage','--disable-gpu']`.
- Point `baseURL` at the app started with `sandbox-run-application`, for example
  `http://127.0.0.1:3000`.

## 3. Write tests

- Use role and text selectors: `page.getByRole('button', { name: 'Save' })`.
- Use `await expect(locator).toBeVisible()` to wait. Do not use fixed `sleep`.
- Stub an unavailable backend with `page.route` and `route.fulfill`.
- Capture errors: `page.on('pageerror', e => errors.push(e.message))` and assert it is empty.
- A full example is in `references/example.spec.ts`.

## 4. Run and read the result

Point the artifact output at the git-ignored platform directory so transient results never enter
the diff:

```
mkdir -p .kratis/test-results
npx playwright test --output .kratis/test-results > .kratis/test-results/pw.log 2>&1; echo "exit=$?"; tail -n 30 .kratis/test-results/pw.log
```

- Exit 0 means pass. Exit 1 means a test failed. Read the tail for the failing test name.

## 5. Publish evidence to the user

Never commit test output to the diff. `.kratis/` is git-excluded, so artifacts written there stay
out of the change set. Report the outcome through the normal channels instead:

- **Test summary** — report pass/fail counts and the failing test names as a normal agent message
  (stdout). This is the primary result; no file is needed.
- **Full transcript** — if the user needs the log, pass it to the control plane as a markdown
  attachment (see `sandbox-publish-to-user`) instead of committing it.
- **Screenshot thumbnail in the activity log** — copy the screenshot into the publish spool as an
  image attachment so the user sees it without opening the diff:

```
mkdir -p .kratis/publish
cp .kratis/test-results/home.png .kratis/publish/attachment-shot-1.png
cat > .kratis/publish/attachment-shot-1.png.meta.json <<'EOF'
{"attachmentId": "shot-1", "kind": "image", "title": "Home page", "contentType": "image/png"}
EOF
```

- **Trace and raw artifacts** — keep them under `.kratis/test-results/` (git-ignored). Open a trace
  with `npx playwright show-trace .kratis/test-results/.../trace.zip`. Do not `git add` them.

A headless agent cannot use `PWDEBUG=1` or the Inspector. Screenshots, traces, and video are the
debug loop.

## 6. Limits

- Headed mode needs `xvfb-run` and `xauth`. Do not use it for normal runs.
- `/dev/shm` is 64 MB. Keep `--disable-dev-shm-usage`; Playwright adds it by default.
- WebGL runs on a software rasteriser. Canvas and WebGL assertions work but are slow.
- A long browser run can exceed one turn. Keep each test under 30 s and output bounded.

Config and example: `references/playwright.config.ts`, `references/example.spec.ts`.

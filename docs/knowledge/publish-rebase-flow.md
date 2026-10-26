# Publish & Rebase Flow

Git publish for sandbox executions. Target-state design and details: [`docs/plans/publish-state-reconciliation.md`](../plans/publish-state-reconciliation.md).

UI: `web/src/components/session/diff/`
RPCs: `sidecar/rpc/git_publish.go`

## Target-State Rules

`SandboxExecution.targetBranch` is the branch the PR merges into (example: `main`).
The control plane copies it from `Repository.branch` at execution creation.
The default is `main`.

1. **Rebase is a separate step before publish.**
   If the default branch has advanced since checkout or the last rebase, the state is `REBASE_REQUIRED`, and publish is disabled until `rebase` succeeds.
2. **Rebase updates the execution base.**
   On clean rebase, the workspace is updated, the execution base SHA advances to the new default-branch SHA, and publish becomes enabled (`UP_TO_DATE`).
3. **Live state decides publish actions.**
   Git does not decide PR lifecycle. The live provider check decides whether to update an open PR, open a new PR, or open a new branch.

| Live PR state | Target | Branch rule |
|---|---|---|
| No prior PR | `OPEN_PR` | Requested branch. If it exists with no PR, update it under a lease. |
| OPEN | `UPDATE_PR` | Current head branch, force-with-lease on its observed SHA. |
| CLOSED, not merged | `OPEN_PR` | Same head branch, lease on its observed SHA. The user edits before publish. |
| MERGED | `OPEN_PR_NEW_BRANCH` | `kratis/<executionId>-<n>`. The merged branch is not changed. |
| Provider unavailable, prior PR exists | none | Blocked with `PROVIDER_UNAVAILABLE`. |

`env.checkout` always clones with an `origin` remote.
The connector persists the checked-out branch to `<credentials-dir>/diff_base_branch` and the base commit SHA to `<credentials-dir>/execution_base_sha`.
A resume never re-runs `env.checkout`, so without persistence the base would be unknown.

The sidecar treats a missing `origin` as a new repository, diffing against the root
commit (`git rev-list --max-parents=0 HEAD`). A workspace with an `origin` remote is
never treated as new: when no branch is recorded the base is git's own `origin/HEAD`
(its default branch), falling back to the root commit only when the remote has no
resolvable default branch (a new repository given a remote but never pushed).
If the target branch is missing, `resolveBaseRef` returns an error.
The diff push never falls back to `HEAD` or `HEAD~1`.

## Diff storage and the read path

The sidecar pushes the diff manifest (`env.diff_manifest`) after each workspace
change, before execution completion, and after every (re)registration.
The manifest lists each changed file with the sha of its diff section.
The control plane asks for missing sections (`env.diff_sections`), stores each
section in blob storage at `diffs/{executionId}/{sha}`, and commits the
manifest to `execution_diff_snapshots`. There is no other diff transport.

The diff view (`/diff/summary`, `/diff/file`, `/diff/export`) is served only
from that persisted copy and never calls the connector — diffs stay viewable
while the sandbox sleeps, disconnects, or terminates. Only hunk context
expansion (`/diff/context`) reads live file content from a connected sandbox.

The publish flow (`SandboxExecutionPublishService`) pulls a fresh summary via
`env.git_observe_publish` because it must stage and commit the sandbox's current
working tree at publish time.

## New repository publish

Until first publish, a new repository has `newRepoName` set and no remote.
The Publish dialog collects the name and visibility.
`publishPullRequest` or `pushBranch` then calls `ensureRemoteRepository`.

1. Resolve the provider from `newRepoCredential.providerMetadata.provider`.
2. If the credential is SSH or generic, show Download Patch instead of this path.
3. Derive the owner from the credential.
4. Reject a GitHub App installation on a personal account.
5. Validate the name against the team's registered repositories.
6. Call `RepoProvider.createRepository`.
7. Reuse an existing repository only if it has no commits.
8. If it has commits, fail with `RemoteRepositoryExistsException` (HTTP 409).
9. Call `env.git_set_remote` to set `origin`.
10. Push the root commit to `refs/heads/<defaultBranch>`.
11. Fetch.
12. Persist the repository and link it to the execution.
13. Clear `newRepoName` and `newRepoCredential`.
14. Run the normal push and PR flow.

The owner comes from the credential: GitHub user or App account, GitLab namespace or `gitlabGroup`, Bitbucket workspace, Azure org and project.

Visibility is a create-time argument only. Do not persist it.
Hide the control for Azure DevOps.

## Multiple pull requests

An execution can publish more than one pull request.
`SandboxExecution.publishedPrs` keeps every published PR: `prNumber`, `prUrl`, `headBranch`,
`baseBranch`, `status` (`OPEN`, `MERGED`, `CLOSED`), and `createdAt`.
The newest entry is the active PR.
`sandbox_execution_published_prs` (Liquibase `0.1/015`) stores the rows; the old
`published_pr_number` and `published_pr_url` columns are dropped after their values are copied.

The git remote decides the state; the database is a cache for rendering.

1. `publish-capabilities` calls `env.git_observe_publish` for the target branch head and
   reads live PR state from the provider.
2. `publish-capabilities` writes the merged or closed status to the stored history and returns
   `publishedPrs`, `previousPrMerged`, and `suggestedNewBranch`.
3. The publish dialog offers **Update existing PR #X** while the PR is open, and
   **Create a new Pull Request** by default when it is merged or closed.
4. `publish-pr` accepts `createNewPr` and `newBranchName`. An explicit request for a new PR, or a
   latest PR that is not open, opens a new PR instead of pushing to the old branch.
5. `env.git_push` derives a fresh branch (`kratis/<executionId>-<n>`) when the requested branch is
   already merged into the target. The result reports `requestedBranch`, `branchCreated`, and
   `previousBranchMerged`.

Branch names chosen by the user are kept unless the sidecar had to derive one.

## Squash

`env.git_push` creates the delta commit before any rebase.
The squash base is the merge-base of the candidate base and HEAD.
Do not squash onto the current `origin/<target>`.

| Mode | Action |
|------|--------|
| First publish with squash | Soft-reset to `merge-base(origin/<target>, HEAD)` and commit |
| Republish with squash | Soft-reset to `merge-base(origin/<feature>, HEAD)` and commit |
| No squash | Commit working-tree changes only. Keep agent commits. |

This keeps upstream target commits and external feature commits in history.
The rebase then replays only the delta.

## Rebase on publish

Sidecar push order:

1. Fetch `origin`.
2. Create the delta commit.
3. Reset the local branch with `git checkout -B <branch>`.
4. If `origin/<feature>` advanced, rebase onto it.
5. If `origin/<target>` is not an ancestor, rebase onto it.
6. On republish, push with `--force-with-lease` on the fetched tip.

The delta commit is a normal commit, so rebase replays it.

On conflict, abort the rebase.
Return `-32001 REBASE_CONFLICT` (HTTP 409).
The delta commit remains.
For `RUNNING` or `IDLE` executions, the dialog offers **Ask agent to rebase & retest**.
The prompt tells the agent to rebase onto `origin/<target>`.
The agent must resolve conflicts and re-run tests.
Re-run a completed execution by hand.

## Protocol

`env.git_push` includes `targetBranch`.
See `protocol/environment/schemas/requests/git_push.schema.yaml`.
It returns `requestedBranch`, `branchCreated`, and `previousBranchMerged` with the commit data.

`env.git_set_remote` takes `remoteUrl`, `defaultBranch`, and `executionId`.
It returns `status`, `defaultBranch`, and `seedCommit`.

Keep the schema, the Go structs, and the Java records in sync.
`TestEnvironmentProtocolParamsMatchSchema` enforces this.

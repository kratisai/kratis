# Publish & Rebase Flow

Git publish for sandbox executions.

UI: `web/src/components/session/diff/`
RPCs: `sidecar/rpc/git.go`

## Diff bases

`SandboxExecution.targetBranch` is the branch the PR merges into (example: `main`).
The control plane copies it from `Repository.branch` at execution creation.
The default is `main`.
It is a property of the execution, not a request parameter.
If a row has no value, use the repository branch, then `main`.

| State | Base | Result |
|-------|------|--------|
| Diff view and first publish | `merge-base(origin/<target>, HEAD)` | Full branch vs target (the PR) |
| Republish (`publishedBranch` set) | `merge-base(origin/<feature>, HEAD)` | Unpushed changes only |
| New repository (no `origin`) | Root commit | All work vs the root commit |

The diff view always uses the first-publish base (three-dot vs the target).
It does not show changes that merged into the target after this branch diverged.

`env.checkout` always clones with an `origin` remote.
Provisioning runs `git init` with no remote.
The sidecar treats a missing `origin` as a new repository.
`resolveDiffBase` then returns `git rev-list --max-parents=0 HEAD`.
Never treat a repository with a remote as new.
If the target branch is missing, `resolveBaseRef` returns an error.
`env.git_diff_summary` and the diff push never fall back to `HEAD` or `HEAD~1`.

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

The publish flow (`SandboxExecutionPublishService`) still pulls a fresh summary
via `env.git_diff_summary` because it must stage and commit the sandbox's
current working tree at publish time.

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
See `protocol/environment/schemas/requests/git_push.schema.json`.

`env.git_set_remote` takes `remoteUrl`, `defaultBranch`, and `executionId`.
It returns `status`, `defaultBranch`, and `seedCommit`.

Keep the schema, the Go structs, and the Java records in sync.
`TestEnvironmentProtocolParamsMatchSchema` enforces this.

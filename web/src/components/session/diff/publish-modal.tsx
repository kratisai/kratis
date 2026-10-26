import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlertCircle,
  CheckCircle2,
  Download,
  ExternalLink,
  GitPullRequest,
  Loader2,
  RefreshCw,
} from 'lucide-react'
import { useEffect, useState } from 'react'
import { toast } from 'sonner'

import type { ExecutionStatus } from '@/lib/execution-api'
import type {
  PublishCapabilities,
  PublishPrRequest,
  PullRequestResult,
  PushBranchResponse,
  RepositoryVisibility,
} from '@/types/diff-types'

import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { useCredentials } from '@/hooks/use-credentials'
import { ApiError } from '@/lib/auth-api'
import { steerExecution } from '@/lib/diff-api'
import {
  downloadPatchFile,
  fetchPublishCapabilities,
  publishPullRequest,
  pushBranch,
  rebaseExecution,
} from '@/lib/publish-api'

export interface PublishModalProps {
  chatId: string
  executionId: string
  executionStatus?: ExecutionStatus
  onOpenChange: (open: boolean) => void
  open: boolean
}

interface PublishActionError {
  isPlanChanged?: boolean
  isProviderUnavailable?: boolean
  isRebaseConflict?: boolean
  message: string
}

export function PublishModal({
  chatId,
  executionId,
  executionStatus,
  onOpenChange,
  open,
}: PublishModalProps) {
  const [branchName, setBranchName] = useState('')
  const [commitMessage, setCommitMessage] = useState('')
  const [body, setBody] = useState('')
  const [isDraft, setIsDraft] = useState(false)
  const [isSquash, setIsSquash] = useState(true)
  const [actionError, setActionError] = useState<null | PublishActionError>(null)
  const [prResult, setPrResult] = useState<null | PullRequestResult>(null)
  const [pushResult, setPushResult] = useState<null | PushBranchResponse>(null)
  const [isRefreshingAfterRebase, setIsRefreshingAfterRebase] = useState(false)

  const [repositoryName, setRepositoryName] = useState('')
  const [visibility, setVisibility] = useState<RepositoryVisibility>('PRIVATE')
  const [credentialId, setCredentialId] = useState('')

  const queryClient = useQueryClient()
  const capabilitiesQueryKey = ['publish-capabilities', chatId, executionId] as const

  const markPublishedInCache = () => {
    queryClient.setQueryData<PublishCapabilities>(capabilitiesQueryKey, (old) => {
      if (!old) return old
      return {
        ...old,
        changeSet: { additions: 0, deletions: 0, digest: old.changeSet?.digest ?? '', files: [] },
        commitCount: 0,
        hasUncommittedChanges: false,
        stats: {
          ...old.stats,
          additions: 0,
          commitsAhead: 0,
          deletions: 0,
          formattedSummary: 'There are no changes to publish',
          hasChanges: false,
          stagedFiles: 0,
          unstagedFiles: 0,
        },
        suggestedBody: '',
        suggestedTitle: '',
      }
    })
  }

  const capabilitiesQuery = useQuery({
    enabled: open,
    queryFn: () => fetchPublishCapabilities(chatId, executionId),
    queryKey: capabilitiesQueryKey,
  })
  const { data: credentials } = useCredentials()

  const capabilities = capabilitiesQuery.data
  const stats = capabilities?.stats
  const supportsPr = capabilities?.supportsPullRequests ?? false
  const target = capabilities?.target
  const isRebaseRequired = capabilities?.status === 'REBASE_REQUIRED'
  const isUpToDate = capabilities?.status === 'UP_TO_DATE'
  const incomingCommits = capabilities?.incomingCommits ?? 0
  const commitCount = capabilities?.commitCount ?? 0
  const hasUncommittedChanges = capabilities?.hasUncommittedChanges ?? false
  const changeSet = capabilities?.changeSet
  const defaultBaseBranch = capabilities?.defaultBaseBranch || 'main'

  const updatingExisting = target?.kind === 'UPDATE_PR'
  const creatingPr = supportsPr && !updatingExisting
  const hasChanges = stats?.hasChanges ?? true
  const canEscalateToAgent = executionStatus === 'RUNNING' || executionStatus === 'IDLE'
  const isNewRepo = capabilities?.newRepo ?? false
  const canCreateRemote = capabilities?.canCreateRepository ?? false
  const visibilityOptions = capabilities?.visibilityOptions ?? []
  const patchOnlyNewRepo = isNewRepo && !canCreateRemote
  const showSquashCheckbox = commitCount > 1 || hasUncommittedChanges

  const rebaseMutation = useMutation({
    mutationFn: () => rebaseExecution(chatId, executionId),
    onError: (err: Error) => {
      setIsRefreshingAfterRebase(false)
      setActionError(toActionError(err, 'Failed to rebase workspace onto default branch'))
      toast.error('Rebase failed')
    },
    onSuccess: async () => {
      setActionError(null)
      setIsRefreshingAfterRebase(true)
      try {
        const result = await capabilitiesQuery.refetch()
        if (result.error) {
          toast.error('Rebased, but failed to refresh the repository status')
          return
        }
        toast.success(`Successfully rebased workspace onto ${defaultBaseBranch}`)
      } finally {
        setIsRefreshingAfterRebase(false)
      }
    },
  })

  const escalateMutation = useMutation({
    mutationFn: () =>
      steerExecution(chatId, executionId, {
        prompt: `The publish to the remote repository failed because your changes conflict with the latest commits on ${defaultBaseBranch}. Rebase your branch onto origin/${defaultBaseBranch}, resolve the conflicts, re-run the tests, and make sure everything passes before the changes are pushed again.`,
      }),
    onError: (err: Error) => {
      toast.error(err.message || 'Failed to dispatch rebase & retest to the agent')
    },
    onSuccess: () => {
      setActionError(null)
      toast.success('Rebase & retest dispatched to the agent')
    },
  })

  useEffect(() => {
    if (open) {
      setPrResult(null)
      setPushResult(null)
      setActionError(null)
      setIsDraft(false)
      setIsSquash(true)
      setCredentialId('')
      setIsRefreshingAfterRebase(false)
    }
  }, [open, executionId])

  useEffect(() => {
    if (!open) return
    const first = credentials?.[0]?.id
    if (first) setCredentialId((current) => (current ? current : first))
  }, [open, credentials])

  useEffect(() => {
    if (!open || !capabilitiesQuery.data) return
    const data = capabilitiesQuery.data
    setBranchName(
      data.target?.branchName ||
        data.publishedBranch ||
        `kratis/feature-${executionId.slice(0, 8)}`,
    )
    setCommitMessage(data.suggestedTitle)
    setBody(data.suggestedBody)
    setRepositoryName(data.suggestedRepositoryName || '')
    setVisibility(data.visibilityOptions[0] ?? 'PRIVATE')
    if (data.commitCount && data.commitCount > 1) {
      setIsSquash(true)
    }
  }, [open, capabilitiesQuery.data, executionId])

  const pushMutation = useMutation({
    mutationFn: () =>
      pushBranch(chatId, executionId, {
        branchName: branchName.trim(),
        commitMessage: commitMessage.trim() || undefined,
        credentialId: isNewRepo && credentialId.trim() ? credentialId.trim() : undefined,
        squash: isSquash,
      }),
    onError: (err: Error) => {
      setActionError(toActionError(err, 'Failed to push branch to remote repository'))
      toast.error('Failed to push branch')
    },
    onSuccess: (data) => {
      markPublishedInCache()
      setPushResult(data)
      setActionError(null)
      toast.success(`Successfully pushed to branch ${data.branchName}`)
    },
  })

  const publishPrMutation = useMutation({
    mutationFn: () => {
      const request: PublishPrRequest = {
        branchName: branchName.trim(),
        digest: capabilities?.digest,
        squash: isSquash,
        title: commitMessage.trim(),
      }
      if (isNewRepo) {
        request.repositoryName = repositoryName.trim()
        request.visibility = visibility
        if (credentialId.trim()) {
          request.credentialId = credentialId.trim()
        }
      }
      if (creatingPr) {
        request.body = body.trim()
        request.draft = isDraft
      }
      return publishPullRequest(chatId, executionId, request)
    },
    onError: (err: Error) => {
      const parsed = toActionError(err, 'Failed to publish changes to remote repository')
      setActionError(parsed)
      if (parsed.isPlanChanged) {
        void capabilitiesQuery.refetch()
      }
      toast.error(updatingExisting ? 'Failed to push changes' : 'Failed to create Pull Request')
    },
    onSuccess: (data) => {
      markPublishedInCache()
      setPrResult(data)
      setActionError(null)
      toast.success(
        updatingExisting
          ? `Pushed changes to Pull Request #${data.prNumber}`
          : `Pull Request #${data.prNumber} created successfully!`,
      )
    },
  })

  const isPending =
    pushMutation.isPending || publishPrMutation.isPending || rebaseMutation.isPending

  const handleExportPatch = async () => {
    try {
      await downloadPatchFile(chatId, executionId)
      toast.success('Patch file downloaded')
    } catch {
      toast.error('Failed to export patch file')
    }
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (patchOnlyNewRepo) {
      return
    }
    if (isRebaseRequired) {
      rebaseMutation.mutate()
      return
    }
    if (!branchName.trim()) {
      toast.error('Branch name is required')
      return
    }
    if (!commitMessage.trim()) {
      toast.error('Commit message is required')
      return
    }
    if (isNewRepo && canCreateRemote && !repositoryName.trim()) {
      toast.error('Repository name is required')
      return
    }
    if (supportsPr) {
      publishPrMutation.mutate()
    } else {
      pushMutation.mutate()
    }
  }

  return (
    <Dialog onOpenChange={onOpenChange} open={open}>
      <DialogContent className="fixed inset-0 top-0 left-0 z-50 flex h-dvh w-screen max-w-none translate-x-0 translate-y-0 flex-col overflow-hidden rounded-none border-none p-4 sm:top-[50%] sm:left-[50%] sm:h-auto sm:max-h-[90vh] sm:w-full sm:max-w-[720px] sm:translate-x-[-50%] sm:translate-y-[-50%] sm:rounded-lg sm:border sm:p-6 sm:shadow-lg md:max-w-[800px]">
        <DialogHeader className="shrink-0">
          <DialogTitle className="flex items-center gap-2">
            <GitPullRequest className="text-primary h-5 w-5" />
            Publish Changes
          </DialogTitle>
          <DialogDescription>
            Publish the agent&apos;s changes to your remote repository or export as a patch.
          </DialogDescription>
        </DialogHeader>

        {capabilitiesQuery.isLoading ? (
          <div className="text-muted-foreground flex min-h-0 flex-1 flex-col items-center justify-center py-12 text-center">
            <Loader2 className="text-primary mb-3 h-7 w-7 animate-spin" />
            <p className="text-sm font-medium">
              Checking repository status and preparing publish details...
            </p>
          </div>
        ) : capabilitiesQuery.isError ? (
          <div className="border-destructive/40 bg-destructive/5 text-destructive flex flex-col items-center justify-center gap-2 rounded-lg border p-6 text-center">
            <AlertCircle className="h-6 w-6" />
            <p className="text-sm font-semibold">Unable to load publish details</p>
            <p className="text-muted-foreground text-xs">
              {capabilitiesQuery.error instanceof Error
                ? capabilitiesQuery.error.message
                : 'Something went wrong while fetching the repository status.'}
            </p>
            <Button
              className="mt-2 h-7 text-xs"
              onClick={() => void capabilitiesQuery.refetch()}
              size="sm"
              variant="outline"
            >
              Try Again
            </Button>
          </div>
        ) : (
          <div className="min-h-0 flex-1 space-y-4 overflow-y-auto py-2">
            {/* Status & Target Bar */}
            {rebaseMutation.isPending ? (
              <div className="flex items-start gap-2 rounded-md border border-sky-500/40 bg-sky-500/10 p-3 text-xs text-sky-700 dark:text-sky-400">
                <Loader2 className="mt-0.5 h-4 w-4 shrink-0 animate-spin" />
                <div>
                  <span className="font-semibold">
                    {isRefreshingAfterRebase
                      ? 'Rebase complete. Refreshing repository status...'
                      : `Rebasing onto ${defaultBaseBranch}...`}
                  </span>
                  <p className="text-muted-foreground mt-0.5">
                    {isRefreshingAfterRebase
                      ? 'Reading the updated commit and pull request state from the sandbox.'
                      : 'The sandbox is replaying your commits onto the latest default branch commits.'}
                  </p>
                </div>
              </div>
            ) : (
              isRebaseRequired && (
                <div className="flex items-start justify-between gap-3 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-xs text-amber-700 dark:text-amber-400">
                  <div className="flex items-start gap-2">
                    <RefreshCw className="mt-0.5 h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
                    <div>
                      <span className="font-semibold">
                        {defaultBaseBranch} has {incomingCommits} new commit
                        {incomingCommits === 1 ? '' : 's'}. Rebase required before publish.
                      </span>
                      <p className="text-muted-foreground mt-0.5">
                        Your changes will be rebased onto origin/{defaultBaseBranch} before
                        publishing.
                      </p>
                    </div>
                  </div>
                  <Button
                    className="h-7 shrink-0 gap-1.5 text-xs"
                    disabled={isPending}
                    onClick={() => rebaseMutation.mutate()}
                    size="sm"
                    variant="outline"
                  >
                    Rebase onto {defaultBaseBranch}
                  </Button>
                </div>
              )
            )}

            {isUpToDate && hasChanges && target && (
              <p className="text-muted-foreground flex items-center gap-1.5 text-xs">
                <GitPullRequest className="h-3.5 w-3.5" />
                {target.kind === 'UPDATE_PR'
                  ? `Updates PR #${target.prNumber}`
                  : target.kind === 'OPEN_PR_NEW_BRANCH'
                    ? `Opens a new PR on ${target.branchName}`
                    : 'Opens a new PR'}
              </p>
            )}

            {/* Zero Changes Notice */}
            {!hasChanges && (
              <div className="flex items-start gap-2 rounded-md border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-600 dark:text-amber-400">
                <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                <div>
                  <span className="font-semibold">There are no changes to publish.</span>
                  <p className="text-muted-foreground mt-0.5">
                    The workspace matches the branch and has no unpushed commits.
                  </p>
                </div>
              </div>
            )}

            {patchOnlyNewRepo && (
              <div className="flex items-start gap-2 rounded-md border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-600 dark:text-amber-400">
                <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                <div>
                  <span className="font-semibold">
                    Automatic repository creation is unavailable.
                  </span>
                  <p className="text-muted-foreground mt-0.5">
                    Creating a remote repository requires a personal access token or app credential.
                    Use Download Patch to apply the agent&apos;s changes to a repository you create.
                  </p>
                </div>
              </div>
            )}

            {/* ChangeSet Summary */}
            {changeSet && changeSet.files.length > 0 && (
              <div className="border-border/60 bg-muted/20 rounded-md border p-3">
                <div className="mb-2 flex items-center justify-between text-xs font-semibold">
                  <span>Changed files ({changeSet.files.length})</span>
                  <span className="text-muted-foreground">
                    +{changeSet.additions} / -{changeSet.deletions} lines
                  </span>
                </div>
                <div className="max-h-36 space-y-1 overflow-y-auto text-xs">
                  {changeSet.files.map((file) => (
                    <div
                      className="text-muted-foreground hover:text-foreground flex items-center justify-between py-0.5 font-mono"
                      key={file.path}
                    >
                      <span className="truncate">{file.path}</span>
                      <span className="bg-muted shrink-0 rounded px-1 text-[10px] uppercase">
                        {file.status}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Success State: PR Created / Updated */}
            {prResult && (
              <div className="space-y-3 rounded-lg border border-emerald-500/30 bg-emerald-500/10 p-4">
                <div className="flex items-center gap-2 text-sm font-medium text-emerald-600 dark:text-emerald-400">
                  <CheckCircle2 className="h-5 w-5 shrink-0" />
                  {updatingExisting ? 'Changes Pushed' : 'Pull Request Created Successfully'}
                </div>
                <p className="text-muted-foreground text-xs">
                  Your changes have been pushed to branch{' '}
                  <code className="font-mono">{prResult.headBranch}</code>
                  {updatingExisting ? (
                    <>
                      {' '}
                      against <code className="font-mono">{prResult.baseBranch}</code>.
                    </>
                  ) : (
                    <>
                      {' '}
                      and a Pull Request was opened against{' '}
                      <code className="font-mono">{prResult.baseBranch}</code>.
                    </>
                  )}
                </p>
                <div className="pt-1">
                  <a
                    className="inline-flex items-center gap-1.5 rounded-md bg-emerald-600 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-emerald-700"
                    href={prResult.prUrl}
                    rel="noreferrer"
                    target="_blank"
                  >
                    Open PR #{prResult.prNumber}
                    <ExternalLink className="h-3.5 w-3.5" />
                  </a>
                </div>
              </div>
            )}

            {/* Success State: Branch Pushed */}
            {pushResult && !prResult && (
              <div className="space-y-2 rounded-lg border border-blue-500/30 bg-blue-500/10 p-4">
                <div className="flex items-center gap-2 text-sm font-medium text-blue-600 dark:text-blue-400">
                  <CheckCircle2 className="h-5 w-5 shrink-0" />
                  Branch Pushed Successfully
                </div>
                <p className="text-muted-foreground text-xs">
                  Pushed commit{' '}
                  <code className="font-mono">{pushResult.commitSha.slice(0, 7)}</code> to branch{' '}
                  <code className="font-mono">{pushResult.branchName}</code> ({pushResult.remoteRef}
                  ).
                </p>
              </div>
            )}

            {/* Error Message */}
            {actionError && (
              <div className="border-destructive/30 bg-destructive/10 text-destructive space-y-2 rounded-lg border p-3 text-xs">
                <div className="flex items-start gap-2">
                  <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                  <span>{actionError.message}</span>
                </div>
                {actionError.isRebaseConflict && canEscalateToAgent && (
                  <div className="border-destructive/20 flex items-center justify-between gap-2 border-t pt-2">
                    <span className="text-destructive">
                      The agent can rebase onto the updated target branch and re-run the tests.
                    </span>
                    <Button
                      className="h-7 shrink-0 gap-1.5 text-xs"
                      disabled={escalateMutation.isPending}
                      onClick={() => escalateMutation.mutate()}
                      size="sm"
                      variant="destructive"
                    >
                      {escalateMutation.isPending && (
                        <Loader2 className="h-3.5 w-3.5 animate-spin" />
                      )}
                      <RefreshCw className="h-3.5 w-3.5" />
                      Ask agent to rebase &amp; retest
                    </Button>
                  </div>
                )}
              </div>
            )}

            {/* Publish Form */}
            {!prResult && !pushResult && hasChanges && (
              <form className="space-y-4" onSubmit={handleSubmit}>
                {isNewRepo && canCreateRemote && (
                  <div className="border-border/60 bg-muted/20 space-y-3 rounded-lg border p-3">
                    <div className="flex items-center gap-2">
                      <GitPullRequest className="text-primary h-4 w-4" />
                      <span className="text-xs font-semibold">Remote Repository</span>
                    </div>

                    <div className="space-y-1.5">
                      <Label htmlFor="repo-name">Repository Name</Label>
                      <Input
                        disabled={isPending}
                        id="repo-name"
                        onChange={(e) => setRepositoryName(e.target.value)}
                        placeholder="repository-name"
                        value={repositoryName}
                      />
                    </div>

                    {visibilityOptions.length > 1 && (
                      <div className="space-y-1.5">
                        <Label htmlFor="repo-visibility">Visibility</Label>
                        <Select
                          disabled={isPending}
                          onValueChange={(val) => setVisibility(val as RepositoryVisibility)}
                          value={visibility}
                        >
                          <SelectTrigger id="repo-visibility">
                            <SelectValue placeholder="Select visibility" />
                          </SelectTrigger>
                          <SelectContent>
                            {visibilityOptions.map((opt) => (
                              <SelectItem key={opt} value={opt}>
                                {opt.charAt(0) + opt.slice(1).toLowerCase()}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>
                    )}

                    {credentials && credentials.length > 0 && (
                      <div className="space-y-1.5">
                        <Label htmlFor="repo-credential">Git Credential</Label>
                        <Select
                          disabled={isPending}
                          onValueChange={setCredentialId}
                          value={credentialId}
                        >
                          <SelectTrigger id="repo-credential">
                            <SelectValue placeholder="Select credential" />
                          </SelectTrigger>
                          <SelectContent>
                            {credentials.map((cred) => (
                              <SelectItem key={cred.id} value={cred.id}>
                                {cred.name} ({cred.type})
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>
                    )}
                  </div>
                )}

                <div className="space-y-1.5">
                  <Label htmlFor="branch-name">Branch name</Label>
                  <Input
                    disabled={isPending}
                    id="branch-name"
                    onChange={(e) => setBranchName(e.target.value)}
                    value={branchName}
                  />
                </div>

                <div className="space-y-1.5">
                  <Label htmlFor="commit-message">
                    {creatingPr ? 'Pull request title' : 'Commit message'}
                  </Label>
                  <Input
                    disabled={isPending}
                    id="commit-message"
                    onChange={(e) => setCommitMessage(e.target.value)}
                    value={commitMessage}
                  />
                </div>

                {creatingPr && (
                  <div className="space-y-1.5">
                    <Label htmlFor="pr-body">Pull request description</Label>
                    <Textarea
                      className="min-h-[100px] resize-y"
                      disabled={isPending}
                      id="pr-body"
                      onChange={(e) => setBody(e.target.value)}
                      value={body}
                    />
                  </div>
                )}

                {showSquashCheckbox && (
                  <div className="flex items-center space-x-2 pt-1">
                    <Checkbox
                      checked={isSquash}
                      disabled={isPending}
                      id="squash-commits"
                      onCheckedChange={(checked) => setIsSquash(Boolean(checked))}
                    />
                    <Label
                      className="text-muted-foreground cursor-pointer text-xs font-normal"
                      htmlFor="squash-commits"
                    >
                      Squash commits into a single commit before push
                    </Label>
                  </div>
                )}

                <DialogFooter className="border-border/50 bg-card sticky bottom-0 flex flex-col-reverse gap-2 border-t pt-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] sm:flex-row sm:items-center sm:justify-between">
                  {!patchOnlyNewRepo && (
                    <Button
                      className="text-muted-foreground hover:text-foreground gap-1.5 text-xs"
                      disabled={isPending}
                      onClick={() => void handleExportPatch()}
                      type="button"
                      variant="ghost"
                    >
                      <Download className="h-3.5 w-3.5" />
                      Download Patch (.patch)
                    </Button>
                  )}

                  <div className="flex items-center justify-end gap-2">
                    <Button
                      disabled={isPending}
                      onClick={() => onOpenChange(false)}
                      type="button"
                      variant="outline"
                    >
                      Cancel
                    </Button>

                    {isRebaseRequired ? (
                      <Button
                        className="gap-1.5"
                        disabled={isPending}
                        onClick={() => rebaseMutation.mutate()}
                        type="button"
                        variant="default"
                      >
                        {rebaseMutation.isPending && (
                          <Loader2 className="h-3.5 w-3.5 animate-spin" />
                        )}
                        {isRefreshingAfterRebase
                          ? 'Refreshing status...'
                          : rebaseMutation.isPending
                            ? 'Rebasing...'
                            : `Rebase onto ${defaultBaseBranch}`}
                      </Button>
                    ) : (
                      <Button className="gap-1.5" disabled={isPending || !hasChanges} type="submit">
                        {isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                        {creatingPr
                          ? 'Create Pull Request'
                          : updatingExisting
                            ? 'Update PR'
                            : 'Publish'}
                      </Button>
                    )}
                  </div>
                </DialogFooter>
              </form>
            )}

            {(prResult || pushResult || !hasChanges) && (
              <DialogFooter className="pt-2">
                <Button onClick={() => onOpenChange(false)} type="button">
                  Close
                </Button>
              </DialogFooter>
            )}
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}

function toActionError(err: Error, fallback: string): PublishActionError {
  const is409 = err instanceof ApiError && err.status === 409
  const is503 = err instanceof ApiError && err.status === 503
  const msg = err.message || fallback
  return {
    isPlanChanged: is409 && msg.includes('changed'),
    isProviderUnavailable: is503,
    isRebaseConflict: is409 && msg.includes('conflict'),
    message: msg,
  }
}

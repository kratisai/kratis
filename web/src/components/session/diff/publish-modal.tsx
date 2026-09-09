import { useMutation, useQuery } from '@tanstack/react-query'
import {
  AlertCircle,
  CheckCircle2,
  Download,
  ExternalLink,
  GitBranch,
  GitPullRequest,
  Loader2,
  RefreshCw,
} from 'lucide-react'
import { useEffect, useState } from 'react'
import { toast } from 'sonner'

import type { ExecutionStatus } from '@/lib/execution-api'
import type { PublishPrRequest, PullRequestResult, PushBranchResponse } from '@/types/diff-types'

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
import { Textarea } from '@/components/ui/textarea'
import { ApiError } from '@/lib/auth-api'
import { steerExecution } from '@/lib/diff-api'
import {
  downloadPatchFile,
  fetchPublishCapabilities,
  publishPullRequest,
  pushBranch,
} from '@/lib/publish-api'

export interface PublishModalProps {
  chatId: string
  executionId: string
  executionStatus?: ExecutionStatus
  onOpenChange: (open: boolean) => void
  open: boolean
}

interface PublishActionError {
  isRebaseConflict: boolean
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
  const [prResult, setPrResult] = useState<null | PullRequestResult>(null)
  const [pushResult, setPushResult] = useState<null | PushBranchResponse>(null)
  const [actionError, setActionError] = useState<null | PublishActionError>(null)

  const capabilitiesQuery = useQuery({
    enabled: open,
    queryFn: () => fetchPublishCapabilities(chatId, executionId),
    queryKey: ['publish-capabilities', chatId, executionId],
  })

  const capabilities = capabilitiesQuery.data
  const stats = capabilities?.stats
  const supportsPr = capabilities?.supportsPullRequests ?? false
  const alreadyPublished = capabilities?.publishedPrNumber != null
  const branchLocked = capabilities?.publishedBranch != null
  const creatingPr = supportsPr && !alreadyPublished
  const baseBranch = capabilities?.defaultBaseBranch || ''
  const hasChanges = stats?.hasChanges ?? true
  const canEscalateToAgent = executionStatus === 'RUNNING' || executionStatus === 'IDLE'

  const escalateMutation = useMutation({
    mutationFn: () =>
      steerExecution(chatId, executionId, {
        prompt: `The publish to the remote repository failed because your changes conflict with the latest commits on ${baseBranch || 'the target branch'}. Rebase your branch onto origin/${baseBranch || 'the target branch'}, resolve the conflicts, re-run the tests, and make sure everything passes before the changes are pushed again.`,
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
    }
  }, [open, executionId])

  useEffect(() => {
    if (!open || !capabilitiesQuery.data) return
    const data = capabilitiesQuery.data
    setBranchName(data.publishedBranch || `kratis/feature-${executionId.slice(0, 8)}`)
    setCommitMessage(data.suggestedTitle)
    setBody(data.suggestedBody)
    if (data.stats.commitsAhead > 1) {
      setIsSquash(true)
    }
  }, [open, capabilitiesQuery.data, executionId])

  const pushMutation = useMutation({
    mutationFn: () =>
      pushBranch(chatId, executionId, {
        branchName: branchName.trim(),
        commitMessage: commitMessage.trim() || undefined,
        squash: isSquash,
      }),
    onError: (err: Error) => {
      setActionError(toActionError(err, 'Failed to push branch to remote repository'))
      toast.error('Failed to push branch')
    },
    onSuccess: (data) => {
      setPushResult(data)
      setActionError(null)
      toast.success(`Successfully pushed to branch ${data.branchName}`)
    },
  })

  const publishPrMutation = useMutation({
    mutationFn: () => {
      const request: PublishPrRequest = {
        branchName: branchName.trim(),
        squash: isSquash,
        title: commitMessage.trim(),
      }
      if (creatingPr) {
        request.body = body.trim()
        request.draft = isDraft
      }
      return publishPullRequest(chatId, executionId, request)
    },
    onError: (err: Error) => {
      setActionError(toActionError(err, 'Failed to publish changes to remote repository'))
      toast.error(alreadyPublished ? 'Failed to push changes' : 'Failed to create Pull Request')
    },
    onSuccess: (data) => {
      setPrResult(data)
      setActionError(null)
      toast.success(
        alreadyPublished
          ? `Pushed changes to Pull Request #${data.prNumber}`
          : `Pull Request #${data.prNumber} created successfully!`,
      )
    },
  })

  const isPending = pushMutation.isPending || publishPrMutation.isPending

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
    if (!branchName.trim()) {
      toast.error('Branch name is required')
      return
    }
    if (!commitMessage.trim()) {
      toast.error('Commit message is required')
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
      {/* Full-screen on mobile; h-dvh tracks the visual viewport so the dialog shrinks above the keyboard */}
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
            {/* Live Git Stats Banner */}
            {stats && (
              <div
                className={`flex items-center gap-2 rounded-md border px-3 py-2 text-xs font-medium ${
                  !hasChanges
                    ? 'border-amber-500/30 bg-amber-500/10 text-amber-600 dark:text-amber-400'
                    : 'border-border/60 bg-muted/40 text-foreground'
                }`}
              >
                <GitBranch className="text-muted-foreground h-3.5 w-3.5 shrink-0" />
                <span>{stats.formattedSummary}</span>
              </div>
            )}

            {/* Zero Changes Notice */}
            {!hasChanges && (
              <div className="flex items-start gap-2 rounded-md border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-600 dark:text-amber-400">
                <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                <div>
                  <span className="font-semibold">There are no changes to publish.</span>
                  <p className="text-muted-foreground mt-0.5">
                    The working tree is clean and no new commits are ahead of the base branch.
                  </p>
                </div>
              </div>
            )}

            {/* Success State: PR Created / Updated */}
            {prResult && (
              <div className="space-y-3 rounded-lg border border-emerald-500/30 bg-emerald-500/10 p-4">
                <div className="flex items-center gap-2 text-sm font-medium text-emerald-600 dark:text-emerald-400">
                  <CheckCircle2 className="h-5 w-5 shrink-0" />
                  {alreadyPublished ? 'Changes Pushed' : 'Pull Request Created Successfully'}
                </div>
                <p className="text-muted-foreground text-xs">
                  Your changes have been pushed to branch{' '}
                  <code className="font-mono">{prResult.headBranch}</code>
                  {alreadyPublished ? (
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

            {/* Success State: Branch Pushed (Generic / Push only) */}
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

            {!prResult && !pushResult && (
              <>
                {capabilities && (
                  <div
                    className="border-border/60 bg-muted/40 text-foreground flex items-start gap-2 rounded-md border px-3 py-2 text-xs"
                    data-testid="publish-plan"
                  >
                    {supportsPr ? (
                      <GitPullRequest className="text-muted-foreground mt-0.5 h-3.5 w-3.5 shrink-0" />
                    ) : (
                      <GitBranch className="text-muted-foreground mt-0.5 h-3.5 w-3.5 shrink-0" />
                    )}
                    <span>
                      {creatingPr && (
                        <>
                          Pushes <code className="font-mono">{branchName}</code> and opens a pull
                          request against <code className="font-mono">{baseBranch}</code>.
                        </>
                      )}
                      {alreadyPublished && (
                        <>
                          Pushes new commits to <code className="font-mono">{branchName}</code> and
                          updates pull request{' '}
                          <a
                            className="text-primary underline"
                            href={capabilities.publishedPrUrl}
                            rel="noreferrer"
                            target="_blank"
                          >
                            #{capabilities.publishedPrNumber}
                          </a>
                          .
                        </>
                      )}
                      {!supportsPr && (
                        <>
                          {branchLocked ? (
                            <>
                              Pushes new commits to <code className="font-mono">{branchName}</code>.
                            </>
                          ) : (
                            <>
                              Pushes <code className="font-mono">{branchName}</code> to the remote
                              repository.
                            </>
                          )}
                          <span className="text-muted-foreground mt-0.5 block">
                            Automated pull requests are not supported for{' '}
                            {capabilities.repositoryType} repositories.
                          </span>
                        </>
                      )}
                    </span>
                  </div>
                )}

                <form className="space-y-4" onSubmit={handleSubmit}>
                  <div className="space-y-1.5">
                    <Label className="text-xs" htmlFor="branch-name">
                      Feature Branch <span className="text-destructive">*</span>
                    </Label>
                    <Input
                      className="font-mono text-xs"
                      disabled={isPending || branchLocked}
                      id="branch-name"
                      onChange={(e) => setBranchName(e.target.value)}
                      placeholder="kratis/feature-branch"
                      required
                      value={branchName}
                    />
                    {branchLocked && (
                      <p className="text-muted-foreground text-[11px]">
                        Locked after the first publish — new commits are pushed to this branch.
                      </p>
                    )}
                  </div>

                  <div className="space-y-1.5">
                    <Label className="text-xs" htmlFor="commit-message">
                      Commit Message <span className="text-destructive">*</span>
                    </Label>
                    <Input
                      disabled={isPending}
                      id="commit-message"
                      onChange={(e) => setCommitMessage(e.target.value)}
                      placeholder="feat: implement feature"
                      required
                      value={commitMessage}
                    />
                    <p className="text-muted-foreground text-[11px]">
                      {creatingPr
                        ? 'Also used as the pull request title when a new pull request is opened.'
                        : 'Used as the commit message for the pushed changes.'}
                    </p>
                  </div>

                  {creatingPr && (
                    <div className="space-y-1.5">
                      <Label className="text-xs" htmlFor="pr-body">
                        Description / Summary
                      </Label>
                      <Textarea
                        className="min-h-[90px] resize-y font-mono text-xs"
                        disabled={isPending}
                        id="pr-body"
                        onChange={(e) => setBody(e.target.value)}
                        placeholder="Explain the changes..."
                        rows={4}
                        value={body}
                      />
                    </div>
                  )}

                  {creatingPr && (
                    <div className="flex items-center space-x-2 pt-1">
                      <Checkbox
                        checked={isDraft}
                        disabled={isPending}
                        id="pr-draft"
                        onCheckedChange={(checked) => setIsDraft(Boolean(checked))}
                      />
                      <Label
                        className="text-muted-foreground cursor-pointer text-xs font-normal"
                        htmlFor="pr-draft"
                      >
                        Create pull request as draft
                      </Label>
                    </div>
                  )}

                  {stats && stats.commitsAhead > 1 && (
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
                        Squash {stats.commitsAhead} commits into a single commit before push
                      </Label>
                    </div>
                  )}

                  <DialogFooter className="border-border/50 bg-card sticky bottom-0 flex flex-col-reverse gap-2 border-t pt-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] sm:flex-row sm:items-center sm:justify-between">
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

                    <div className="flex items-center justify-end gap-2">
                      <Button
                        disabled={isPending}
                        onClick={() => onOpenChange(false)}
                        type="button"
                        variant="outline"
                      >
                        Cancel
                      </Button>

                      <Button className="gap-1.5" disabled={isPending || !hasChanges} type="submit">
                        {isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                        {creatingPr
                          ? 'Create Pull Request'
                          : alreadyPublished
                            ? `Push to PR #${capabilities.publishedPrNumber}`
                            : 'Push Branch'}
                      </Button>
                    </div>
                  </DialogFooter>
                </form>
              </>
            )}

            {(prResult || pushResult) && (
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
  return {
    isRebaseConflict: err instanceof ApiError && err.status === 409,
    message: err.message || fallback,
  }
}

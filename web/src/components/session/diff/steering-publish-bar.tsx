import { GitPullRequest, Loader2, MessageSquare, Send, Sparkles, X } from 'lucide-react'
import { useRef, useState } from 'react'
import { toast } from 'sonner'

import type { ExecutionStatus } from '@/lib/execution-api'
import type { DiffCommentDraft } from '@/types/diff-types'

import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { useSteerExecution } from '@/hooks/use-diff'
import { cn } from '@/lib/utils'
import { useDiffReviewStore } from '@/store/diff-review-store'

import { PublishModal } from './publish-modal'

const EMPTY_COMMENTS: DiffCommentDraft[] = []

interface SteeringPublishBarProps {
  chatId: string
  executionId: string
  executionStatus?: ExecutionStatus
  isAutoHidden?: boolean
}

export function SteeringPublishBar({
  chatId,
  executionId,
  executionStatus,
  isAutoHidden = false,
}: SteeringPublishBarProps) {
  const [promptText, setPromptText] = useState('')
  const [isFocused, setIsFocused] = useState(false)
  const [isPublishModalOpen, setIsPublishModalOpen] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const activeCommentBox = useDiffReviewStore((state) => state.activeCommentBox)
  const draftComments = useDiffReviewStore(
    (state) => state.draftComments[executionId] ?? EMPTY_COMMENTS,
  )
  const clearDraftComments = useDiffReviewStore((state) => state.clearDraftComments)

  const steerMutation = useSteerExecution(chatId, executionId)

  // Completely hide when typing an inline comment
  if (activeCommentBox !== null) {
    return null
  }

  const isReadyToPublish = executionStatus === 'IDLE' || executionStatus === 'COMPLETED'
  const canSteer = executionStatus === 'RUNNING' || executionStatus === 'IDLE'
  const commentCount = draftComments.length

  const handleSendFeedback = async () => {
    if (!promptText.trim() && commentCount === 0) return

    try {
      await steerMutation.mutateAsync({
        comments: draftComments.map((c) => ({
          codeSnippet: c.codeSnippet,
          comment: c.comment,
          line: c.line,
          path: c.path,
        })),
        prompt: promptText.trim() || undefined,
      })

      clearDraftComments(executionId)
      setPromptText('')
      setIsFocused(false)
      toast.success(
        commentCount > 0
          ? `Dispatched review with ${commentCount} comments to agent`
          : 'Steering guidance sent to agent',
      )
    } catch {
      toast.error('Failed to dispatch steering guidance')
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault()
      void handleSendFeedback()
    }
  }

  return (
    <div
      className={cn(
        'pointer-events-none fixed inset-x-0 bottom-0 z-30 flex justify-center p-3 transition-all duration-300 md:absolute',
        'pb-[max(0.75rem,env(safe-area-inset-bottom))]',
        isAutoHidden && !isFocused && 'translate-y-full opacity-0',
      )}
      data-testid="steering-publish-bar"
    >
      <div className="pointer-events-auto w-full max-w-4xl">
        {!isFocused && !promptText.trim() ? (
          /* Unfocused / Idle Single-Line Bar */
          <div
            className="border-border/80 bg-card/90 text-muted-foreground hover:border-primary/50 hover:bg-card flex cursor-pointer items-center justify-between gap-3 rounded-full border px-4 py-2 text-xs shadow-xs transition-all"
            onClick={() => {
              setIsFocused(true)
              setTimeout(() => textareaRef.current?.focus(), 50)
            }}
          >
            <div className="flex items-center gap-2">
              <Sparkles className="text-primary h-3.5 w-3.5 shrink-0" />
              <span>
                {commentCount > 0
                  ? `${commentCount} review ${commentCount === 1 ? 'comment' : 'comments'} pending · Tap to steer agent...`
                  : 'Tap to steer agent, provide feedback, or publish changes...'}
              </span>
            </div>

            <div className="flex items-center gap-2">
              {isReadyToPublish && (
                <Button
                  className="h-6 px-2.5 text-xs"
                  onClick={(e) => {
                    e.stopPropagation()
                    setIsPublishModalOpen(true)
                  }}
                  size="sm"
                  variant="default"
                >
                  <GitPullRequest className="mr-1 h-3 w-3" />
                  Publish
                </Button>
              )}
            </div>
          </div>
        ) : (
          /* Focused / Expanded Guidance Box */
          <div className="border-border/80 bg-card/95 rounded-xl border p-3 shadow-lg backdrop-blur-md">
            {/* Header: comment count badge + collapse button */}
            <div className="mb-2 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="text-xs font-semibold">Steering Guidance</span>
                {commentCount > 0 && (
                  <span className="bg-primary/10 text-primary flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium">
                    <MessageSquare className="h-3 w-3" />
                    {commentCount} inline comment{commentCount > 1 ? 's' : ''}
                  </span>
                )}
              </div>
              <Button
                aria-label="Collapse steering bar"
                className="text-muted-foreground hover:text-foreground h-6 w-6 p-0"
                onClick={() => setIsFocused(false)}
                size="sm"
                variant="ghost"
              >
                <X className="h-3.5 w-3.5" />
              </Button>
            </div>

            {/* Instruction Textarea */}
            <Textarea
              aria-label="Steering guidance input"
              className="max-h-36 min-h-[60px] resize-none border-none p-0 text-xs shadow-none focus-visible:ring-0"
              disabled={steerMutation.isPending || !canSteer}
              onChange={(e) => setPromptText(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={
                commentCount > 0
                  ? 'Add high-level steering instructions to accompany inline comments (Ctrl+Enter to send)...'
                  : 'Describe what the agent should fix, refactor, or add (Ctrl+Enter to send)...'
              }
              ref={textareaRef}
              value={promptText}
            />

            {/* Actions Toolbar */}
            <div className="border-border/50 mt-2 flex items-center justify-between border-t pt-2">
              <span className="text-muted-foreground text-[10px]">
                Press <kbd className="bg-muted rounded px-1 font-mono text-[9px]">Ctrl+Enter</kbd>{' '}
                to send
              </span>

              <div className="flex items-center gap-2">
                {isReadyToPublish && (
                  <Button
                    className="h-7 text-xs"
                    onClick={() => setIsPublishModalOpen(true)}
                    size="sm"
                    variant="outline"
                  >
                    <GitPullRequest className="mr-1 h-3.5 w-3.5" />
                    Publish
                  </Button>
                )}

                <Button
                  className="h-7 text-xs"
                  disabled={
                    steerMutation.isPending ||
                    (!promptText.trim() && commentCount === 0) ||
                    !canSteer
                  }
                  onClick={() => void handleSendFeedback()}
                  size="sm"
                >
                  {steerMutation.isPending ? (
                    <>
                      <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" />
                      Sending...
                    </>
                  ) : commentCount > 0 ? (
                    <>
                      <Send className="mr-1 h-3.5 w-3.5" />
                      Send Feedback ({commentCount})
                    </>
                  ) : (
                    <>
                      <Send className="mr-1 h-3.5 w-3.5" />
                      Send Guidance
                    </>
                  )}
                </Button>
              </div>
            </div>
          </div>
        )}
      </div>

      <PublishModal
        chatId={chatId}
        executionId={executionId}
        executionStatus={executionStatus}
        onOpenChange={setIsPublishModalOpen}
        open={isPublishModalOpen}
      />
    </div>
  )
}

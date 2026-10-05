import { MessageSquare, Pencil, Trash2, X } from 'lucide-react'
import { useState } from 'react'

import type { DiffCommentDraft } from '@/types/diff-types'

import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { useDiffReviewStore } from '@/store/diff-review-store'

interface DiffInlineCommentProps {
  codeSnippet?: string
  comments: DiffCommentDraft[]
  executionId: string
  isDrafting: boolean
  line: number
  onCloseDraft: () => void
  path: string
  side?: 'new' | 'old'
}

export function DiffInlineComment({
  codeSnippet,
  comments,
  executionId,
  isDrafting,
  line,
  onCloseDraft,
  path,
  side,
}: DiffInlineCommentProps) {
  const [draftText, setDraftText] = useState('')
  const [editingCommentId, setEditingCommentId] = useState<null | string>(null)
  const [editText, setEditText] = useState('')
  const addDraftComment = useDiffReviewStore((state) => state.addDraftComment)
  const removeDraftComment = useDiffReviewStore((state) => state.removeDraftComment)
  const updateDraftComment = useDiffReviewStore((state) => state.updateDraftComment)

  const handleSave = () => {
    if (!draftText.trim()) return
    addDraftComment(executionId, path, line, draftText.trim(), codeSnippet, undefined, side)
    setDraftText('')
    onCloseDraft()
  }

  const handleEditSave = () => {
    if (!editingCommentId || !editText.trim()) return
    updateDraftComment(executionId, editingCommentId, editText.trim())
    setEditingCommentId(null)
    setEditText('')
  }

  const handleDelete = (commentId: string) => {
    removeDraftComment(executionId, commentId)
    if (editingCommentId === commentId) {
      setEditingCommentId(null)
      setEditText('')
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>, onSave: () => void) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault()
      onSave()
    }
  }

  if (!isDrafting && comments.length === 0) {
    return null
  }

  return (
    <div
      className="border-primary/30 bg-card mx-4 my-1 space-y-2.5 rounded-md border p-3 text-xs shadow-sm select-text"
      data-testid={`inline-comment-row-${line}`}
    >
      {/* Existing comments on this line */}
      {comments.map((c) => (
        <div
          className="border-border/50 flex items-start justify-between gap-2 border-b pb-2 last:border-0 last:pb-0"
          key={c.id}
        >
          <div className="min-w-0 flex-1 space-y-1">
            <div className="text-primary flex items-center gap-1.5 font-semibold">
              <MessageSquare className="h-3.5 w-3.5" />
              <span>Line {c.line} Feedback</span>
            </div>
            {c.codeSnippet && (
              <pre className="bg-muted/70 text-muted-foreground rounded p-1.5 font-mono text-[11px] break-words whitespace-pre-wrap">
                {c.codeSnippet}
              </pre>
            )}
            {editingCommentId === c.id ? (
              <div className="space-y-2">
                <Textarea
                  aria-label="Edit review comment"
                  autoFocus
                  className="min-h-[64px] resize-none text-xs focus-visible:ring-1"
                  onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) =>
                    setEditText(e.target.value)
                  }
                  onKeyDown={(e: React.KeyboardEvent<HTMLTextAreaElement>) =>
                    handleKeyDown(e, handleEditSave)
                  }
                  value={editText}
                />
                <div className="flex items-center justify-end gap-2">
                  <Button
                    className="h-7 text-xs"
                    onClick={() => {
                      setEditingCommentId(null)
                      setEditText('')
                    }}
                    size="sm"
                    variant="ghost"
                  >
                    <X className="mr-1 h-3.5 w-3.5" />
                    Cancel
                  </Button>
                  <Button
                    className="h-7 text-xs"
                    disabled={!editText.trim()}
                    onClick={handleEditSave}
                    size="sm"
                  >
                    Save Comment
                  </Button>
                </div>
              </div>
            ) : (
              <p className="text-foreground break-words whitespace-pre-wrap">{c.comment}</p>
            )}
          </div>
          <div className="flex shrink-0 items-start gap-0.5">
            <Button
              aria-label="Edit comment"
              className="text-muted-foreground hover:text-foreground h-6 w-6"
              onClick={() => {
                setEditingCommentId(c.id)
                setEditText(c.comment)
              }}
              size="icon"
              variant="ghost"
            >
              <Pencil className="h-3.5 w-3.5" />
            </Button>
            <Button
              aria-label="Delete comment"
              className="text-muted-foreground hover:text-destructive h-6 w-6"
              onClick={() => handleDelete(c.id)}
              size="icon"
              variant="ghost"
            >
              <Trash2 className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      ))}

      {/* Active drafting box */}
      {isDrafting && (
        <div className="space-y-2 pt-1">
          {codeSnippet && (
            <pre className="bg-muted/70 text-muted-foreground rounded p-1.5 font-mono text-[11px] break-words whitespace-pre-wrap">
              {codeSnippet}
            </pre>
          )}
          <Textarea
            aria-label="Write a review comment"
            autoFocus
            className="min-h-[64px] resize-none text-xs focus-visible:ring-1"
            onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => setDraftText(e.target.value)}
            onKeyDown={(e: React.KeyboardEvent<HTMLTextAreaElement>) =>
              handleKeyDown(e, handleSave)
            }
            placeholder="Add a review note for the agent... (Ctrl+Enter to save)"
            value={draftText}
          />
          <div className="flex items-center justify-end gap-2">
            <Button className="h-7 text-xs" onClick={onCloseDraft} size="sm" variant="ghost">
              <X className="mr-1 h-3.5 w-3.5" />
              Cancel
            </Button>
            <Button
              className="h-7 text-xs"
              disabled={!draftText.trim()}
              onClick={handleSave}
              size="sm"
            >
              Save Comment
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

import { useNavigate } from '@tanstack/react-router'
import { FileText, Play, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import type { CanvasDocument } from '@/types/canvas-types'

import { Button } from '@/components/ui/button'
import { deleteCanvasDocument } from '@/lib/canvas-api'
import { createSandboxExecution } from '@/lib/execution-api'
import { cn } from '@/lib/utils'
import { useCanvasStore } from '@/store/canvas-store'
import { useExecutionStore } from '@/store/execution-store'

import { CanvasDeleteDialog } from './canvas-delete-dialog'
import { ExecutionLaunchDialog, type LaunchTarget } from './execution-launch-dialog'

interface CanvasTabsProps {
  chatId: string
  className?: string
  docId: string
}

export function CanvasTabs({ chatId, className, docId }: CanvasTabsProps) {
  const navigate = useNavigate()
  const canvases = useCanvasStore((state) => state.canvases)
  const removeCanvasDocument = useCanvasStore((state) => state.removeCanvasDocument)

  const { clearLogs, setTerminalOpen } = useExecutionStore()

  const [isLaunchDialogOpen, setIsLaunchDialogOpen] = useState(false)
  const [docToDelete, setDocToDelete] = useState<CanvasDocument | null>(null)
  const [isDeleting, setIsDeleting] = useState(false)

  // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition -- canvases[chatId] can be undefined at runtime despite the Record type
  const chatCanvases = canvases[chatId] || []

  if (chatCanvases.length === 0) {
    return null
  }

  const handleDelete = async () => {
    if (!docToDelete) return
    const target = docToDelete.documentId
    setIsDeleting(true)
    try {
      await deleteCanvasDocument(chatId, target)
      removeCanvasDocument(chatId, target)
      setDocToDelete(null)
      const remaining = chatCanvases.filter((doc) => doc.documentId !== target)
      if (remaining.length > 0) {
        await navigate({
          params: { docId: remaining[0].documentId, id: chatId },
          to: '/chats/$id/canvas/$docId',
        })
      } else {
        await navigate({ params: { id: chatId }, to: '/chats/$id' })
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err)
      toast.error(`Failed to delete document: ${message}`)
    } finally {
      setIsDeleting(false)
    }
  }

  const handleLaunch = async (target: LaunchTarget) => {
    if (!target.modelProviderId) {
      toast.error('No model provider selected')
      return
    }
    if (!target.modelName) {
      toast.error('No model selected')
      return
    }

    setTerminalOpen(true)
    setIsLaunchDialogOpen(false)

    try {
      const execution = target.providerId
        ? await createSandboxExecution(chatId, {
            canvasId: docId,
            credentialId: target.credentialId,
            harness: target.harness,
            modelName: target.modelName,
            modelProviderId: target.modelProviderId,
            providerId: target.providerId,
          })
        : await createSandboxExecution(chatId, {
            canvasId: docId,
            credentialId: target.credentialId,
            environmentId: target.environmentId,
            harness: target.harness,
            modelName: target.modelName,
            modelProviderId: target.modelProviderId,
          })

      clearLogs(execution.id)
      await navigate({
        params: { executionId: execution.id, id: chatId },
        to: '/chats/$id/executions/$executionId',
      })
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err)
      toast.error(`Failed to launch execution: ${message}`)
    }
  }

  const activeDoc = chatCanvases.find((doc) => doc.documentId === docId)
  const canExecute = activeDoc?.canvasType === 'SPEC'

  return (
    <div className={cn('flex items-center justify-between gap-2 border-b px-3 py-1.5', className)}>
      <div className="flex min-w-0 items-center gap-1.5">
        <FileText className="text-primary h-3.5 w-3.5 shrink-0" />
        <span className="truncate text-sm font-medium">{activeDoc?.title ?? docId}</span>
        {activeDoc?.canvasType === 'SPEC' && activeDoc.repoLabel ? (
          <span className="text-muted-foreground shrink-0 truncate text-xs">
            ({activeDoc.repoLabel})
          </span>
        ) : null}
      </div>

      {activeDoc ? (
        <div className="flex shrink-0 items-center gap-1">
          {canExecute ? (
            <Button
              className="h-8 gap-1.5 bg-emerald-600 px-3 text-xs text-white hover:bg-emerald-700"
              onClick={() => setIsLaunchDialogOpen(true)}
              size="sm"
            >
              <Play className="h-3.5 w-3.5 fill-current" />
              Run
            </Button>
          ) : null}
          <Button
            aria-label="Delete document"
            className="hover:bg-destructive/10 text-muted-foreground hover:text-destructive h-8 w-8 px-0"
            onClick={() => setDocToDelete(activeDoc)}
            size="sm"
            title="Delete document"
            type="button"
            variant="ghost"
          >
            <Trash2 className="h-3.5 w-3.5" />
          </Button>
        </div>
      ) : null}

      <ExecutionLaunchDialog
        docId={docId}
        onLaunch={(target) => {
          void handleLaunch(target)
        }}
        onOpenChange={setIsLaunchDialogOpen}
        open={isLaunchDialogOpen}
      />

      <CanvasDeleteDialog
        documentTitle={docToDelete?.title ?? ''}
        isPending={isDeleting}
        onCancel={() => setDocToDelete(null)}
        onConfirm={() => {
          void handleDelete()
        }}
        open={docToDelete !== null}
      />
    </div>
  )
}

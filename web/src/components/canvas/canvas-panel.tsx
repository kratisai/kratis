import { CanvasDocument } from '@/components/canvas/canvas-document'
import { CanvasTabs } from '@/components/canvas/canvas-tabs'
import { useCanvasStore } from '@/store/canvas-store'

interface CanvasPanelProps {
  chatId: string
  docId: string
}

export function CanvasPanel({ chatId, docId }: CanvasPanelProps) {
  const canvases = useCanvasStore((state) => state.canvases)

  // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition -- canvases[chatId] can be undefined at runtime despite the Record type
  const chatCanvases = canvases[chatId] || []

  if (chatCanvases.length === 0) {
    return (
      <div className="flex h-full items-center justify-center">
        <p className="text-muted-foreground">No canvas documents yet</p>
      </div>
    )
  }

  // The URL docId is the source of truth; fall back to the first document when the
  // param is absent or points at a document that no longer exists.
  const resolvedDocId = chatCanvases.some((doc) => doc.documentId === docId)
    ? docId
    : chatCanvases[0].documentId

  return (
    <div className="flex h-full flex-col">
      <CanvasTabs chatId={chatId} docId={resolvedDocId} />
      <div className="flex-1 overflow-hidden">
        <CanvasDocument chatId={chatId} documentId={resolvedDocId} />
      </div>
    </div>
  )
}

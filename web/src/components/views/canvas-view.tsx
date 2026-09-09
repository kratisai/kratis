import { useParams } from '@tanstack/react-router'

import { CanvasPanel } from '@/components/canvas/canvas-panel'

export function CanvasView() {
  const { docId, id } = useParams({ strict: false })

  if (!id) {
    return (
      <div className="flex h-full items-center justify-center">
        <p className="text-muted-foreground">No chat selected</p>
      </div>
    )
  }

  return <CanvasPanel chatId={id} docId={docId ?? ''} />
}

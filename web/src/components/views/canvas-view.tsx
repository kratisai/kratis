import { useParams } from '@tanstack/react-router'
import { useEffect } from 'react'

import { CanvasPanel } from '@/components/canvas/canvas-panel'
import { useCanvasStore } from '@/store/canvas-store'

export function CanvasView() {
  const { docId, id } = useParams({ strict: false })
  const clearCanvasActivity = useCanvasStore((state) => state.clearCanvasActivity)

  useEffect(() => {
    if (id && docId) {
      clearCanvasActivity(id, docId)
    }
  }, [clearCanvasActivity, docId, id])

  if (!id) {
    return (
      <div className="flex h-full items-center justify-center">
        <p className="text-muted-foreground">No chat selected</p>
      </div>
    )
  }

  return <CanvasPanel chatId={id} docId={docId ?? ''} />
}

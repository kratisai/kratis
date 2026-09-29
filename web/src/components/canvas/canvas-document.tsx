import { Check, Copy } from 'lucide-react'
import { useState } from 'react'

import { MarkdownMessage } from '@/components/chat/markdown-message'
import { Button } from '@/components/ui/button'
import { copyToClipboard } from '@/lib/clipboard'
import { useCanvasStore } from '@/store/canvas-store'
import { useChatStore } from '@/store/chat-store'

interface CanvasDocumentProps {
  chatId: string
  documentId: string
}

export function CanvasDocument({ chatId, documentId }: CanvasDocumentProps) {
  const canvases = useCanvasStore((state) => state.canvases)
  const messages = useChatStore((state) => state.messages)
  const [copied, setCopied] = useState(false)

  const handleCopy = (content: string) => {
    void copyToClipboard(content).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }

  // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition -- messages[chatId] can be undefined at runtime despite the Record type
  const chatMessagesForUpdateCheck = chatId ? messages[chatId] || [] : []
  const isUpdating = chatMessagesForUpdateCheck.some(
    (msg) =>
      msg.role === 'working' &&
      msg.items.some(
        (item) =>
          item.type === 'tool' &&
          (item.toolName === 'write_canvas' || item.toolName === 'patch_canvas') &&
          item.status === 'running',
      ),
  )

  // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition -- canvases[chatId] can be undefined at runtime despite the Record type
  const chatCanvases = canvases[chatId] || []
  const doc = chatCanvases.find((d) => d.documentId === documentId)

  if (!doc) {
    return (
      <div className="flex h-full items-center justify-center">
        <p className="text-muted-foreground">Document not found</p>
      </div>
    )
  }

  return (
    <div className="relative min-h-full overflow-visible p-4 md:h-full md:overflow-auto">
      <div className="absolute top-4 right-4 z-50 flex items-center gap-2">
        {isUpdating && (
          <div className="border-primary/20 bg-background/80 text-primary flex animate-pulse items-center gap-2 rounded-full border px-3 py-1.5 text-xs shadow-md backdrop-blur-md">
            <div className="bg-primary h-2 w-2 animate-ping rounded-full" />
            <span>Agent updating canvas...</span>
          </div>
        )}
        <Button
          aria-label="Copy document"
          className="text-muted-foreground opacity-70 hover:opacity-100 focus-visible:opacity-100"
          onClick={() => handleCopy(doc.content)}
          size="icon"
          variant="ghost"
        >
          {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
        </Button>
      </div>
      <div className="mx-auto max-w-4xl">
        <MarkdownMessage content={doc.content} />
      </div>
    </div>
  )
}

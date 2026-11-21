import { Check, Copy } from 'lucide-react'
import { memo } from 'react'

import { AgentWorkingBlock } from '@/components/chat/agent-working-block'
import { MarkdownMessage } from '@/components/chat/markdown-message'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { cn } from '@/lib/utils'
import { type ChatMessage } from '@/store/chat-store'

interface ChatMessageRowProps {
  chatId: string
  isCopied: boolean
  message: ChatMessage
  onCopy: (messageId: string, content: string) => void
}

export const ChatMessageRow = memo(function ChatMessageRow({
  chatId,
  isCopied,
  message,
  onCopy,
}: ChatMessageRowProps) {
  if (message.role === 'working') {
    return (
      <AgentWorkingBlock
        chatId={chatId}
        endTime={message.endTime}
        isPaused={message.isPaused}
        isStreaming={message.isStreaming}
        items={message.items}
        pauseReason={message.pauseReason}
        startTime={message.startTime}
      />
    )
  }

  return (
    <Card
      className={cn(
        'group relative overflow-x-auto',
        message.role === 'user' ? 'ml-auto max-w-[85%]' : 'mr-auto max-w-[85%]',
        message.isError && 'border-destructive/50 bg-destructive/5',
      )}
    >
      <CardContent className="p-3">
        {message.role === 'user' ? (
          <p className="text-sm whitespace-pre-wrap">{message.content}</p>
        ) : message.isError ? (
          <p className="text-destructive text-sm">{message.content}</p>
        ) : (
          <MarkdownMessage content={message.content} />
        )}
      </CardContent>
      {message.role !== 'user' && !message.isError && (
        <Button
          aria-label="Copy message"
          className="text-muted-foreground pointer-events-none absolute right-1.5 bottom-1.5 h-6 w-6 opacity-0 transition-opacity group-hover:pointer-events-auto group-hover:opacity-100 focus-visible:pointer-events-auto focus-visible:opacity-100"
          onClick={() => onCopy(message.id, message.content)}
          size="icon"
          variant="ghost"
        >
          {isCopied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
        </Button>
      )}
    </Card>
  )
})

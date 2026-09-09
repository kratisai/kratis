import { useQueryClient } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { Check, Copy } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import { CanvasPanel } from '@/components/canvas/canvas-panel'
import { AgentWorkingBlock } from '@/components/chat/agent-working-block'
import { MarkdownMessage } from '@/components/chat/markdown-message'
import { UserMessageInput } from '@/components/chat/user-message-input'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { useIsMobile } from '@/hooks/use-is-mobile'
import { copyToClipboard } from '@/lib/clipboard'
import { uuid } from '@/lib/id'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/store/auth-store'
import { useCanvasStore } from '@/store/canvas-store'
import { useChatStore } from '@/store/chat-store'
import { useWebSocketStore } from '@/store/websocket-store'

interface DesignStageViewProps {
  chatId: string
}

export function DesignStageView({ chatId }: DesignStageViewProps) {
  const { docId } = useParams({ strict: false })
  const queryClient = useQueryClient()
  const currentTeamId = useAuthStore((state) => state.currentTeamId)
  const addMessage = useChatStore((state) => state.addMessage)
  const sendMessage = useChatStore((state) => state.sendMessage)
  const isConnected = useWebSocketStore((state) => state.isConnected)
  const messages = useChatStore((state) => state.messages)
  const canvases = useCanvasStore((state) => state.canvases)

  const messagesEndRef = useRef<HTMLDivElement>(null)
  const messagesScrollRef = useRef<HTMLDivElement>(null)
  const isNearBottomRef = useRef(true)
  const isMobile = useIsMobile()
  const [copiedMessageId, setCopiedMessageId] = useState<null | string>(null)

  const handleCopyMessage = (messageId: string, content: string) => {
    void copyToClipboard(content).then(() => {
      setCopiedMessageId(messageId)
      setTimeout(
        () => setCopiedMessageId((current) => (current === messageId ? null : current)),
        1500,
      )
    })
  }

  // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition -- messages[chatId] can be undefined at runtime despite the Record type
  const chatMessages = chatId ? messages[chatId] || [] : []
  const hasMessages = chatMessages.length > 0
  // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition -- canvases[chatId] can be undefined at runtime despite the Record type
  const chatCanvases = chatId ? canvases[chatId] || [] : []
  const hasCanvases = chatCanvases.length > 0

  useEffect(() => {
    // Desktop scrolls the history pane; mobile scrolls the document (so the
    // browser chrome can collapse), which has no pane-level scroll events.
    if (isMobile) {
      const handleWindowScroll = () => {
        const scrollable = document.documentElement
        const distanceFromBottom = scrollable.scrollHeight - window.scrollY - window.innerHeight
        isNearBottomRef.current = distanceFromBottom < 120
      }
      handleWindowScroll()
      window.addEventListener('scroll', handleWindowScroll, { passive: true })
      return () => window.removeEventListener('scroll', handleWindowScroll)
    }

    const container = messagesScrollRef.current
    if (!container) return

    const handleScroll = () => {
      const distanceFromBottom =
        container.scrollHeight - container.scrollTop - container.clientHeight
      isNearBottomRef.current = distanceFromBottom < 120
    }
    handleScroll()
    container.addEventListener('scroll', handleScroll, { passive: true })
    return () => container.removeEventListener('scroll', handleScroll)
  }, [isMobile])

  useEffect(() => {
    if (!isNearBottomRef.current) return
    messagesEndRef.current?.scrollIntoView({ behavior: 'auto', block: 'end' })
  }, [chatMessages])

  const handleSendMessage = (message: string) => {
    if (!chatId) return

    isNearBottomRef.current = true
    addMessage(chatId, {
      content: message,
      id: uuid(),
      role: 'user',
      timestamp: new Date(),
    })
    messagesEndRef.current?.scrollIntoView({ behavior: 'auto', block: 'end' })

    if (!isConnected) return

    sendMessage(chatId, message)
    void queryClient.invalidateQueries({ queryKey: ['chats', currentTeamId] })
  }

  return (
    <div
      className="flex h-full w-full min-w-0 flex-col md:overflow-hidden"
      data-testid="design-stage-view"
    >
      {/* Main Workspace (2-Column Split on Widescreen, Single Panel on Mobile and Small-Desktop) */}
      <div className="flex min-h-0 min-w-0 flex-1 overflow-visible md:overflow-hidden">
        {/* Left Chat Pane */}
        <div
          className={cn(
            'flex min-w-0 flex-1 flex-col overflow-visible md:overflow-hidden',
            hasCanvases && 'xl:max-w-[45%]',
            Boolean(docId) && hasCanvases ? 'hidden xl:flex' : 'flex',
          )}
          data-testid="design-chat-pane"
        >
          {/* Scrollable Chat History */}
          <div
            className="flex-1 overflow-visible p-4 md:overflow-auto"
            data-testid="design-chat-history"
            ref={messagesScrollRef}
          >
            <div className={cn('space-y-4', !hasCanvases && 'mx-auto max-w-3xl')}>
              {!hasMessages && (
                <p className="text-muted-foreground py-8 text-center text-sm">
                  Start a conversation to refine requirements and generate canvas specs.
                </p>
              )}
              {chatMessages.map((msg) =>
                msg.role === 'working' ? (
                  <AgentWorkingBlock
                    endTime={msg.endTime}
                    isStreaming={msg.isStreaming}
                    items={msg.items}
                    key={msg.id}
                    startTime={msg.startTime}
                  />
                ) : (
                  <Card
                    className={cn(
                      'group relative overflow-x-auto',
                      msg.role === 'user' ? 'ml-auto max-w-[85%]' : 'mr-auto max-w-[85%]',
                      msg.isError && 'border-destructive/50 bg-destructive/5',
                    )}
                    key={msg.id}
                  >
                    <CardContent className="p-3">
                      {msg.role === 'user' ? (
                        <p className="text-sm whitespace-pre-wrap">{msg.content}</p>
                      ) : msg.isError ? (
                        <p className="text-destructive text-sm">{msg.content}</p>
                      ) : (
                        <MarkdownMessage content={msg.content} />
                      )}
                    </CardContent>
                    {msg.role !== 'user' && !msg.isError && (
                      <Button
                        aria-label="Copy message"
                        className="text-muted-foreground pointer-events-none absolute right-1.5 bottom-1.5 h-6 w-6 opacity-0 transition-opacity group-hover:pointer-events-auto group-hover:opacity-100 focus-visible:pointer-events-auto focus-visible:opacity-100"
                        onClick={() => handleCopyMessage(msg.id, msg.content)}
                        size="icon"
                        variant="ghost"
                      >
                        {copiedMessageId === msg.id ? (
                          <Check className="h-3.5 w-3.5" />
                        ) : (
                          <Copy className="h-3.5 w-3.5" />
                        )}
                      </Button>
                    )}
                  </Card>
                ),
              )}
              <div ref={messagesEndRef} />
            </div>
          </div>

          {/* User Message Input - sticky so it stays visible while the mobile
              document scrolls through a long conversation */}
          <div
            className="bg-background/95 border-border/60 sticky bottom-0 z-10 border-t p-3 backdrop-blur-xs"
            data-testid="design-chat-composer"
          >
            <div className={cn(!hasCanvases && 'mx-auto max-w-3xl')}>
              <UserMessageInput
                disabled={!isConnected}
                onSend={handleSendMessage}
                placeholder={
                  isConnected ? 'Type a message... (Ctrl+Enter for new line)' : 'Connecting...'
                }
              />
            </div>
          </div>
        </div>

        {/* Right Canvas Pane */}
        {hasCanvases && (
          <div
            className={cn(
              'border-border/60 flex min-w-0 flex-1 flex-col border-l',
              !docId ? 'hidden xl:flex' : 'flex',
            )}
            data-testid="design-canvas-pane"
          >
            <CanvasPanel chatId={chatId} docId={docId ?? chatCanvases[0].documentId} />
          </div>
        )}
      </div>
    </div>
  )
}

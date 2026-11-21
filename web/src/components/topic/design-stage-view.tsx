import { useQueryClient } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { memo, useCallback, useEffect, useRef } from 'react'

import type { CanvasDocument } from '@/types/canvas-types'

import { CanvasPanel } from '@/components/canvas/canvas-panel'
import { ChatMessageRow } from '@/components/chat/chat-message-row'
import { PLAN_REQUEST_MESSAGE, PlanShortcut } from '@/components/chat/plan-shortcut'
import { UserMessageInput } from '@/components/chat/user-message-input'
import { useIsMobile } from '@/hooks/use-is-mobile'
import { useResetAfterDelay } from '@/hooks/use-reset-after-delay'
import { copyToClipboard } from '@/lib/clipboard'
import { uuid } from '@/lib/id'
import { canonicalizeMermaidMarkdown } from '@/lib/mermaid/markdown'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/store/auth-store'
import { useCanvasStore } from '@/store/canvas-store'
import { type ChatMessage, useChatStore } from '@/store/chat-store'
import { useWebSocketStore } from '@/store/websocket-store'

interface DesignStageViewProps {
  chatId: string
}

const NO_MESSAGES: ChatMessage[] = []
const NO_CANVASES: CanvasDocument[] = []

export const DesignStageView = memo(function DesignStageView({ chatId }: DesignStageViewProps) {
  const { docId } = useParams({ strict: false })
  const queryClient = useQueryClient()
  const currentTeamId = useAuthStore((state) => state.currentTeamId)
  const addMessage = useChatStore((state) => state.addMessage)
  const sendMessage = useChatStore((state) => state.sendMessage)
  const isConnected = useWebSocketStore((state) => state.isConnected)
  const chatMessages =
    useChatStore((state): ChatMessage[] | undefined => state.messages[chatId]) ?? NO_MESSAGES
  const sendingChatIds = useChatStore((state) => state.sendingChatIds)
  const chatCanvases =
    useCanvasStore((state): CanvasDocument[] | undefined => state.canvases[chatId]) ?? NO_CANVASES
  const unreadCanvasDocIds = useCanvasStore((state) => state.unreadCanvasDocIds)
  const clearCanvasActivity = useCanvasStore((state) => state.clearCanvasActivity)

  const messagesEndRef = useRef<HTMLDivElement>(null)
  const messagesScrollRef = useRef<HTMLDivElement>(null)
  const isNearBottomRef = useRef(true)
  const isMobile = useIsMobile()
  const isWidescreen = !useIsMobile(1280)
  const [copiedMessageId, setCopiedMessageId] = useResetAfterDelay<null | string>(null, null, 1500)

  const handleCopyMessage = useCallback(
    (messageId: string, content: string) => {
      void copyToClipboard(canonicalizeMermaidMarkdown(content)).then((copied) => {
        if (copied) setCopiedMessageId(messageId)
      })
    },
    [setCopiedMessageId],
  )

  const hasMessages = chatMessages.length > 0
  const hasCanvases = chatCanvases.length > 0

  // The plan shortcut appears once the agent finished responding and no
  // executable plan canvas (SPEC) exists for the chat yet.
  const lastMessage = chatMessages.at(-1)
  const isAgentResponding =
    sendingChatIds.has(chatId) || chatMessages.some((m) => m.role === 'working' && m.isStreaming)
  const hasPlanCanvas = chatCanvases.some((doc) => doc.canvasType === 'SPEC')
  const showPlanShortcut =
    lastMessage?.role === 'assistant' &&
    !lastMessage.isError &&
    !isAgentResponding &&
    !hasPlanCanvas

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

  useEffect(() => {
    if (!chatId) return
    const unread = unreadCanvasDocIds[chatId] ?? []
    if (unread.length === 0) return

    if (docId) {
      if (unread.includes(docId)) {
        clearCanvasActivity(chatId, docId)
      }
    } else if (isWidescreen && chatCanvases.length > 0) {
      const firstDocId = chatCanvases[0].documentId
      if (unread.includes(firstDocId)) {
        clearCanvasActivity(chatId, firstDocId)
      }
    }
  }, [chatId, docId, isWidescreen, chatCanvases, unreadCanvasDocIds, clearCanvasActivity])

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
              {chatMessages.map((msg) => (
                <ChatMessageRow
                  chatId={chatId}
                  isCopied={copiedMessageId === msg.id}
                  key={msg.id}
                  message={msg}
                  onCopy={handleCopyMessage}
                />
              ))}
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
              {showPlanShortcut && (
                <PlanShortcut
                  disabled={!isConnected}
                  onGenerate={() => handleSendMessage(PLAN_REQUEST_MESSAGE)}
                />
              )}
              <UserMessageInput
                disabled={!isConnected}
                isWorking={isAgentResponding}
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
})

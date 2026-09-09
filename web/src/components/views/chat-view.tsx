import { useQueryClient } from '@tanstack/react-query'
import { Outlet, useParams, useRouterState } from '@tanstack/react-router'
import { Maximize2, Minimize2, X } from 'lucide-react'
import { useEffect, useRef } from 'react'

import { Button } from '@/components/ui/button'
import { useChatExecutions } from '@/hooks/use-executions'
import { cn } from '@/lib/utils'
import { useCanvasStore } from '@/store/canvas-store'
import { useChatStore } from '@/store/chat-store'
import { useExecutionStore } from '@/store/execution-store'

import { DesignStageView } from '../topic/design-stage-view'
import { ExecutionStageView } from '../topic/execution-stage-view'
import { TopicStageNav } from '../topic/topic-stage-nav'

const MIN_TERMINAL_HEIGHT = 128
const MAX_TERMINAL_HEIGHT_RATIO = 0.85

const CHAT_EXECUTIONS_QUERY_KEY = 'chat-executions'

export function ChatView() {
  const { docId, id } = useParams({ strict: false })
  const { executionId: routeExecutionId } = useParams({ strict: false })
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  const currentChatId = useChatStore((state) => state.currentChatId)
  const subscribeChat = useChatStore((state) => state.subscribeChat)
  const canvases = useCanvasStore((state) => state.canvases)
  const queryClient = useQueryClient()

  const terminalEndRef = useRef<HTMLDivElement>(null)

  const {
    logs,
    setTerminalFullscreen,
    setTerminalHeight,
    setTerminalOpen,
    terminalFullscreen,
    terminalHeight,
    terminalOpen,
  } = useExecutionStore()

  const chatId = id || currentChatId
  const { data: executions = [] } = useChatExecutions(chatId)
  const latestRunning = [...executions]
    .reverse()
    .find((e) => e.status === 'RUNNING' || e.status === 'IDLE')
  const activeExecutionId = routeExecutionId ?? latestRunning?.id ?? null
  const activeLogs = activeExecutionId ? (logs[activeExecutionId] ?? []) : []
  const showTerminal = terminalOpen && activeExecutionId !== null

  // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition -- canvases[chatId] can be undefined at runtime despite the Record type
  const chatCanvases = chatId ? canvases[chatId] || [] : []
  const currentStage: 'design' | 'execution' = pathname.includes('/executions/')
    ? 'execution'
    : 'design'

  useEffect(() => {
    if (chatId) {
      subscribeChat(chatId)
      void queryClient.invalidateQueries({ queryKey: [CHAT_EXECUTIONS_QUERY_KEY, chatId] })
    }
  }, [chatId, queryClient, subscribeChat])

  useEffect(() => {
    terminalEndRef.current?.scrollIntoView({ behavior: 'auto', block: 'end' })
  }, [activeLogs])

  const handleCloseTerminal = () => {
    setTerminalFullscreen(false)
    setTerminalOpen(false)
  }

  const startTerminalResize = (event: React.PointerEvent<HTMLButtonElement>) => {
    event.preventDefault()
    const startY = event.clientY
    const startHeight = terminalHeight
    const maxHeight = Math.max(
      MIN_TERMINAL_HEIGHT,
      Math.round(window.innerHeight * MAX_TERMINAL_HEIGHT_RATIO),
    )

    const onMove = (moveEvent: PointerEvent) => {
      const nextHeight = startHeight + (startY - moveEvent.clientY)
      setTerminalHeight(Math.min(maxHeight, Math.max(MIN_TERMINAL_HEIGHT, nextHeight)))
    }
    const onUp = () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
  }

  if (!chatId) {
    return (
      <div className="flex h-full items-center justify-center">
        <p className="text-muted-foreground">No chat selected</p>
      </div>
    )
  }

  return (
    // min-height makes short chats fill the mobile viewport below the h-14 header;
    // overflow-hidden only applies to the desktop fixed shell (md:).
    <div
      className="flex min-h-[calc(100dvh-3.5rem)] w-full flex-col overflow-visible md:h-full md:min-h-0 md:overflow-hidden"
      data-testid="chat-view"
    >
      {/* 1. Stage Strip Navigation */}
      <TopicStageNav
        activeDocId={docId}
        activeExecutionId={routeExecutionId}
        canvasCount={chatCanvases.length}
        canvasDocuments={chatCanvases}
        chatId={chatId}
        currentStage={currentStage}
        executions={executions}
      />

      {/* 2. Main Stage Content */}
      <div className="flex flex-1 overflow-visible md:overflow-hidden">
        {currentStage === 'design' ? (
          <DesignStageView chatId={chatId} />
        ) : routeExecutionId ? (
          <ExecutionStageView chatId={chatId} executionId={routeExecutionId} />
        ) : (
          <Outlet />
        )}
      </div>

      {/* 3. Terminal Console Drawer */}
      {showTerminal && (
        <div
          className={cn(
            'flex flex-col overflow-hidden bg-zinc-950 font-mono text-[11px] text-zinc-100',
            terminalFullscreen
              ? 'fixed inset-4 z-50 rounded-lg border border-zinc-800 shadow-2xl'
              : 'border-t',
          )}
          data-testid="terminal-panel"
          style={terminalFullscreen ? undefined : { height: terminalHeight }}
        >
          {!terminalFullscreen && (
            <button
              aria-label="Resize terminal"
              className="group flex h-2 shrink-0 cursor-ns-resize touch-none items-center justify-center border-b border-zinc-800 bg-zinc-900 hover:bg-zinc-800"
              onPointerDown={startTerminalResize}
              type="button"
            >
              <div className="h-1 w-12 rounded-full bg-zinc-700 group-hover:bg-zinc-500" />
            </button>
          )}
          <div className="flex items-center justify-between border-b border-zinc-800 bg-zinc-900 px-4 py-2">
            <div className="flex items-center gap-2 font-semibold text-zinc-400">
              <div className="h-2 w-2 animate-pulse rounded-full bg-emerald-500" />
              <span>Terminal - Sandbox Console</span>
            </div>
            <div className="flex items-center gap-2">
              <Button
                aria-label={terminalFullscreen ? 'Exit fullscreen' : 'Expand terminal'}
                className="h-6 text-zinc-400 hover:bg-zinc-800 hover:text-zinc-100"
                onClick={() => setTerminalFullscreen(!terminalFullscreen)}
                size="sm"
                variant="ghost"
              >
                {terminalFullscreen ? (
                  <Minimize2 className="h-3 w-3" />
                ) : (
                  <Maximize2 className="h-3 w-3" />
                )}
              </Button>
              <Button
                aria-label="Close terminal"
                className="h-6 text-zinc-400 hover:bg-zinc-800 hover:text-zinc-100"
                onClick={handleCloseTerminal}
                size="sm"
                variant="ghost"
              >
                <X className="h-3 w-3" />
              </Button>
            </div>
          </div>
          <div className="flex-1 space-y-1 overflow-y-auto p-4 select-text">
            {activeLogs.length === 0 ? (
              <div className="text-zinc-600 italic">
                No output. Choose an execution target and click 'Run' above.
              </div>
            ) : (
              activeLogs.map((log, index) => {
                let lineClass = 'text-zinc-300'
                if (log.startsWith('[System]')) {
                  lineClass = 'text-blue-400 font-semibold'
                } else if (log.startsWith('[Warning]')) {
                  lineClass = 'text-yellow-500 font-semibold'
                } else if (log.startsWith('[Error]')) {
                  lineClass = 'text-red-400 font-semibold'
                } else if (log.startsWith('[Output]')) {
                  lineClass = 'text-emerald-400 font-medium'
                }
                return (
                  <div className={cn('leading-relaxed whitespace-pre-wrap', lineClass)} key={index}>
                    {log}
                  </div>
                )
              })
            )}
            <div ref={terminalEndRef} />
          </div>
        </div>
      )}
    </div>
  )
}

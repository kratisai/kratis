import { toast } from 'sonner'
import { create } from 'zustand'

import type {
  CanvasResult,
  ChatErrorResult,
  ClientRpcMethods,
  CompleteResult,
  ExecutionActivityResult,
  ExecutionCompleteResult,
  ExecutionHitlRequiredResult,
  ExecutionHitlResolvedResult,
  ExecutionOutputResult,
  ExecutionReplayCompleteResult,
  ExecutionStatusChangedResult,
  IngestionResult,
  JsonRpcResponse,
  MessageChunkResult,
  MessageResult,
  TeamEntityChangedResult,
  TelemetryResult,
  UserEntityChangedResult,
} from '@/types/websocket-types'

import { queryClient } from '@/lib/query-client'
import { useActivityStore } from '@/store/activity-store'
import { useAuthStore } from '@/store/auth-store'
import { useCanvasStore } from '@/store/canvas-store'
import { useChatStore } from '@/store/chat-store'
import { useExecutionStore } from '@/store/execution-store'

const WS_URL = import.meta.env.VITE_WS_URL || '/ws/client'
const HEARTBEAT_INTERVAL_MS = 5 * 60_000
const PONG_TIMEOUT_MS = 30_000
const MAX_RECONNECT_DELAY_MS = 30_000
const MAX_SUBSCRIPTION_RETRIES = 3

const REPOSITORIES_QUERY_KEY = 'repositories'
const CHAT_EXECUTIONS_QUERY_KEY = 'chat-executions'

interface WebSocketState {
  connect: () => void
  disconnect: () => void
  error: null | string
  isConnected: boolean
  isConnecting: boolean
  send: <M extends keyof ClientRpcMethods>(method: M, params: ClientRpcMethods[M]) => void
  subscribe: (teamId: string) => void
  subscribedTeamId: null | string
  unsubscribe: (teamId: string) => void
}

export function teamEntityQueryKey(entity: string, teamId: string): null | string[] {
  switch (entity) {
    case 'CHATS':
      return ['chats', teamId]
    case 'CREDENTIALS':
      return ['credentials', teamId]
    case 'ENVIRONMENTS':
      return ['environments', teamId]
    case 'MODEL_PROVIDERS':
      return ['model-providers', teamId]
    case 'PERMISSIONS':
      return ['permissions', teamId]
    case 'REPOSITORIES':
      return [REPOSITORIES_QUERY_KEY, teamId]
    case 'USAGE':
      return ['team-usage-summary', teamId]
    default:
      return null
  }
}

let messageIdCounter = 0

export function resyncActiveExecution(): void {
  const currentChatId = useChatStore.getState().currentChatId
  if (currentChatId) {
    void queryClient.invalidateQueries({ queryKey: [CHAT_EXECUTIONS_QUERY_KEY, currentChatId] })
  }
}

export const useWebSocketStore = create<WebSocketState>((set, _get) => {
  let ws: null | WebSocket = null
  let reconnectTimer: null | ReturnType<typeof setTimeout> = null
  let reconnectAttempts = 0
  let subscribedTeamId: null | string = null
  let heartbeatTimer: null | ReturnType<typeof setInterval> = null
  let pongTimeoutTimer: null | ReturnType<typeof setTimeout> = null
  let lastInboundAt = 0
  let lastAuthToken: null | string = null
  let hasConnectedOnce = false
  const messageQueue: { method: string; params: Record<string, unknown> }[] = []
  const inboundBuffer: JsonRpcResponse[] = []
  let flushTimer: null | number = null
  const pendingSubscriptionIds = new Set<number>()
  let subscriptionRetries = 0
  let subscriptionRetryTimer: null | ReturnType<typeof setTimeout> = null

  function flushInbound() {
    flushTimer = null
    const batch = inboundBuffer.splice(0, inboundBuffer.length)
    for (const response of batch) {
      dispatchResponse(response)
    }
  }

  function bufferInbound(response: JsonRpcResponse) {
    inboundBuffer.push(response)
    if (flushTimer === null) {
      if (typeof requestAnimationFrame === 'function') {
        flushTimer = requestAnimationFrame(flushInbound)
      } else {
        flushTimer = window.setTimeout(flushInbound, 16)
      }
    }
  }

  function dispatchResponse(response: JsonRpcResponse) {
    if (response.error) {
      if (response.id === 0) {
        set({ error: response.error.message })
        ws?.close()
        return
      }
      if (typeof response.id === 'number' && pendingSubscriptionIds.delete(response.id)) {
        scheduleSubscriptionRetry()
      }
      const errorData = response.error.data as Record<string, unknown> | undefined
      const errorChatId = errorData?.chatId as string | undefined
      const currentChatId = useChatStore.getState().currentChatId

      if (errorChatId && errorChatId === currentChatId) {
        useChatStore.getState().handleErrorResponse(response.error)
      } else {
        toast.error(response.error.message)
      }
      return
    }

    if (response.result?.type) {
      const { type } = response.result
      switch (type) {
        case 'auth':
          set({ isConnected: true })
          // The socket is only usable once the server confirms the session, so
          // (re)establish subscriptions here rather than speculatively on open.
          resubscribe()
          break
        case 'canvas':
          handleCanvasResult(response.result)
          break
        case 'chat_error':
          handleChatErrorResult(response.result)
          break
        case 'chat_subscription':
          confirmSubscription(response.id)
          break
        case 'complete':
          handleCompleteResult(response.result)
          break
        case 'execution_activity':
          handleExecutionActivityResult(response.result)
          break
        case 'execution_complete':
          handleExecutionCompleteResult(response.result)
          break
        case 'execution_hitl_required':
          handleExecutionHitlRequired(response.result)
          break
        case 'execution_hitl_resolved':
          handleExecutionHitlResolved(response.result)
          break
        case 'execution_output':
          handleExecutionOutputResult(response.result)
          break
        case 'execution_replay_complete':
          handleExecutionReplayCompleteResult(response.result)
          break
        case 'execution_status_changed':
          handleExecutionStatusChangedResult(response.result)
          break
        case 'ingestion':
          handleIngestionResult(response.result)
          break
        case 'message':
          handleMessageResult(response.result)
          break
        case 'message_chunk':
          handleMessageChunkResult(response.result)
          break
        case 'subscription':
          confirmSubscription(response.id)
          break
        case 'team_entity_changed':
          handleTeamEntityChangedResult(response.result)
          break
        case 'telemetry':
          handleTelemetryResult(response.result)
          break
        case 'user_entity_changed':
          handleUserEntityChangedResult(response.result)
          break
      }
    }
  }

  function handleMessageResult(messageResult: MessageResult) {
    useChatStore.getState().handleMessageResult(messageResult)
  }

  function handleMessageChunkResult(messageChunkResult: MessageChunkResult) {
    useChatStore.getState().handleMessageChunkResult(messageChunkResult)
  }

  function handleCompleteResult(completeResult: CompleteResult) {
    useChatStore.getState().handleCompleteResult(completeResult)
  }

  function handleChatErrorResult(chatErrorResult: ChatErrorResult) {
    useChatStore.getState().handleChatErrorResult(chatErrorResult)
  }

  function handleTelemetryResult(telemetryResult: TelemetryResult) {
    useChatStore.getState().handleTelemetryEvent(telemetryResult.chatId, telemetryResult)
  }

  function handleExecutionActivityResult(result: ExecutionActivityResult) {
    useActivityStore.getState().handleActivityEvent(result)
  }

  function handleExecutionOutputResult(result: ExecutionOutputResult) {
    const linePrefix = result.stream === 'stderr' ? '[Error]' : '[Output]'
    useExecutionStore.getState().addLog(result.executionId, `${linePrefix} ${result.line}`)
    useActivityStore.getState().handleExecutionOutput(result)
  }

  function handleExecutionReplayCompleteResult(result: ExecutionReplayCompleteResult) {
    useExecutionStore.getState().handleReplayComplete(result)
  }

  function handleExecutionHitlRequired(result: ExecutionHitlRequiredResult) {
    useActivityStore.getState().handleHitlRequired(result)
  }

  function handleExecutionHitlResolved(result: ExecutionHitlResolvedResult) {
    useActivityStore.getState().handleHitlResolved(result)
  }

  function handleCanvasResult(canvasResult: CanvasResult) {
    useCanvasStore.getState().handleCanvasEvent(canvasResult)
  }

  function handleTeamEntityChangedResult(result: TeamEntityChangedResult) {
    if (result.teamId !== useAuthStore.getState().currentTeamId) return
    const queryKey = teamEntityQueryKey(result.entity, result.teamId)
    if (queryKey) {
      void queryClient.invalidateQueries({ queryKey })
    }
    if (result.entity === 'USAGE' || result.entity === 'SANDBOX_EXECUTIONS') {
      void queryClient.invalidateQueries({ queryKey: ['team-usage-summary', result.teamId] })
      void queryClient.invalidateQueries({ queryKey: ['team-usage-logs', result.teamId] })
    }
  }

  function handleUserEntityChangedResult(result: UserEntityChangedResult) {
    if (result.entity === 'TEAMS') {
      void queryClient.invalidateQueries({ queryKey: ['teams'] })
    }
  }

  function sendSubscription(teamId: string, method: 'subscribe' | 'unsubscribe') {
    if (!ws || ws.readyState !== WebSocket.OPEN) return
    const requestId = ++messageIdCounter
    if (method === 'subscribe') {
      pendingSubscriptionIds.add(requestId)
    }
    ws.send(
      JSON.stringify({
        id: requestId,
        jsonrpc: '2.0',
        method,
        params: { teamId },
      }),
    )
  }

  function sendSubscriptions() {
    if (!ws || ws.readyState !== WebSocket.OPEN) return

    if (subscribedTeamId) {
      sendSubscription(subscribedTeamId, 'subscribe')
    }

    const { subscribedChatIds } = useChatStore.getState()
    const currentTeamId = useAuthStore.getState().currentTeamId
    for (const chatId of subscribedChatIds) {
      const requestId = ++messageIdCounter
      pendingSubscriptionIds.add(requestId)
      ws.send(
        JSON.stringify({
          id: requestId,
          jsonrpc: '2.0',
          method: 'chat.subscribe',
          params: { chatId, teamId: currentTeamId || undefined },
        }),
      )
    }
  }

  /**
   * Re-issue every active subscription once the server confirms a session. The server treats
   * repeated subscribe calls as idempotent, so this is safe to run on every (re)authentication
   * and guarantees subscriptions survive reconnects and in-place token refreshes.
   */
  function resubscribe() {
    subscriptionRetries = 0
    sendSubscriptions()
  }

  function confirmSubscription(id: unknown) {
    if (typeof id === 'number') {
      pendingSubscriptionIds.delete(id)
    }
    subscriptionRetries = 0
  }

  function scheduleSubscriptionRetry() {
    if (subscriptionRetries >= MAX_SUBSCRIPTION_RETRIES || subscriptionRetryTimer !== null) {
      return
    }
    subscriptionRetries++
    subscriptionRetryTimer = setTimeout(() => {
      subscriptionRetryTimer = null
      sendSubscriptions()
    }, 1000 * subscriptionRetries)
  }

  function reauthenticate(token: string) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return
    const requestId = ++messageIdCounter
    ws.send(
      JSON.stringify({
        id: requestId,
        jsonrpc: '2.0',
        method: 'auth',
        params: { token },
      }),
    )
  }

  function clearPongTimeout() {
    if (pongTimeoutTimer !== null) {
      clearTimeout(pongTimeoutTimer)
      pongTimeoutTimer = null
    }
  }

  function startHeartbeat() {
    if (heartbeatTimer !== null) return
    heartbeatTimer = setInterval(() => {
      if (!ws || ws.readyState !== WebSocket.OPEN) return
      const requestId = ++messageIdCounter
      const sentAt = Date.now()
      try {
        ws.send(
          JSON.stringify({
            id: requestId,
            jsonrpc: '2.0',
            method: 'ping',
            params: {},
          }),
        )
      } catch (error) {
        console.warn('WebSocket heartbeat send failed; forcing reconnect', error)
        ws.close()
        return
      }

      // A half-open socket keeps ws.OPEN while the server has already dropped the
      // session, so detect a missed pong and reconnect.
      clearPongTimeout()
      pongTimeoutTimer = setTimeout(() => {
        pongTimeoutTimer = null
        if (lastInboundAt <= sentAt) {
          console.warn('WebSocket heartbeat timed out; forcing reconnect')
          ws?.close()
        }
      }, PONG_TIMEOUT_MS)
    }, HEARTBEAT_INTERVAL_MS)
  }

  function stopHeartbeat() {
    if (heartbeatTimer !== null) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
    clearPongTimeout()
  }

  function cleanup() {
    if (ws) {
      ws.onopen = null
      ws.onmessage = null
      ws.onerror = null
      ws.onclose = null
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        ws.close()
      }
      ws = null
    }
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (subscriptionRetryTimer !== null) {
      clearTimeout(subscriptionRetryTimer)
      subscriptionRetryTimer = null
    }
    pendingSubscriptionIds.clear()
    if (flushTimer !== null) {
      if (typeof cancelAnimationFrame === 'function') {
        cancelAnimationFrame(flushTimer)
      } else {
        clearTimeout(flushTimer)
      }
      flushTimer = null
    }
    stopHeartbeat()
    inboundBuffer.length = 0
  }

  function scheduleReconnect() {
    if (reconnectTimer !== null) return
    reconnectAttempts++
    const timeout = Math.min(
      1000 * Math.pow(2, Math.min(reconnectAttempts, 6)),
      MAX_RECONNECT_DELAY_MS,
    )
    reconnectTimer = setTimeout(connect, timeout)
    if (reconnectAttempts >= 5) {
      set({ error: 'Connection lost. Reconnecting…' })
    }
  }

  function connect() {
    if (ws?.readyState === WebSocket.OPEN || ws?.readyState === WebSocket.CONNECTING) {
      return
    }
    if (reconnectTimer !== null) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }

    set({ isConnecting: true })

    try {
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      const host = window.location.host
      const url = WS_URL.startsWith('/') ? `${protocol}//${host}${WS_URL}` : WS_URL

      ws = new WebSocket(url)

      ws.onopen = () => {
        set({ error: null, isConnected: true, isConnecting: false })
        reconnectAttempts = 0
        lastInboundAt = Date.now()
        clearPongTimeout()

        if (hasConnectedOnce) {
          // Broadcasts are not replayed, so refetch to recover events missed while down.
          void queryClient.invalidateQueries()
        }
        hasConnectedOnce = true

        const token = useAuthStore.getState().accessToken
        lastAuthToken = token
        if (token) {
          const requestId = ++messageIdCounter
          ws?.send(
            JSON.stringify({
              id: requestId,
              jsonrpc: '2.0',
              method: 'auth',
              params: { token },
            }),
          )
        }
        startHeartbeat()

        // Subscriptions are (re)established from the auth confirmation, not here: an
        // unauthenticated subscribe is rejected by the server and would be lost.
        resyncActiveExecution()

        while (messageQueue.length > 0 && ws) {
          const queued = messageQueue.shift()
          if (queued) {
            const requestId = ++messageIdCounter
            ws.send(
              JSON.stringify({
                id: requestId,
                jsonrpc: '2.0',
                method: queued.method,
                params: queued.params,
              }),
            )
          }
        }
      }

      ws.onmessage = (event) => {
        lastInboundAt = Date.now()
        clearPongTimeout()
        try {
          bufferInbound(JSON.parse(event.data) as JsonRpcResponse)
        } catch (error) {
          console.error('Failed to parse WebSocket message:', error)
        }
      }

      ws.onerror = () => {
        set({ error: 'Failed to connect to server' })
      }

      ws.onclose = () => {
        cleanup()
        set({ isConnected: false, isConnecting: false })

        useChatStore.getState().handleDisconnect()

        scheduleReconnect()
      }
    } catch (error) {
      set({ error: 'Failed to initialize connection', isConnecting: false })
      console.error('WebSocket connection error:', error)
      scheduleReconnect()
    }
  }

  function disconnect() {
    cleanup()
    reconnectAttempts = 0
    subscriptionRetries = 0
    hasConnectedOnce = false
    messageQueue.length = 0
    set({ error: null, isConnected: false, isConnecting: false })
  }

  function send<M extends keyof ClientRpcMethods>(method: M, params: ClientRpcMethods[M]): void {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      messageQueue.push({ method, params: params as Record<string, unknown> })
      return
    }

    const requestId = ++messageIdCounter
    const request = {
      id: requestId,
      jsonrpc: '2.0',
      method,
      params,
    }

    try {
      ws.send(JSON.stringify(request))
    } catch (error) {
      console.error('Failed to send request:', error)
    }
  }

  function subscribe(teamId: string) {
    if (subscribedTeamId === teamId) {
      return
    }
    subscribedTeamId = teamId
    set({ subscribedTeamId })
    sendSubscription(teamId, 'subscribe')
  }

  function unsubscribe(teamId: string) {
    if (subscribedTeamId !== teamId) {
      return
    }
    subscribedTeamId = null
    set({ subscribedTeamId })
    sendSubscription(teamId, 'unsubscribe')
  }

  if (typeof useAuthStore.subscribe === 'function') {
    useAuthStore.subscribe(() => {
      syncTeamSubscription()
      const currentToken = useAuthStore.getState().accessToken
      if (currentToken !== lastAuthToken) {
        lastAuthToken = currentToken
        if (currentToken) {
          reauthenticate(currentToken)
        }
      }
    })
  }

  return {
    connect,
    disconnect,
    error: null,
    isConnected: false,
    isConnecting: false,
    send,
    subscribe,
    subscribedTeamId: null,
    unsubscribe,
  }
})

export function handleExecutionCompleteResult(result: ExecutionCompleteResult): void {
  const statusText = result.status === 'FAILED' ? 'FAILED' : 'successfully'
  useExecutionStore
    .getState()
    .addLog(
      result.executionId,
      `[System] Command completed ${statusText} with exit code ${result.exitCode}.`,
    )
  useActivityStore.getState().handleExecutionComplete(result)
}

export function handleExecutionStatusChangedResult(result: ExecutionStatusChangedResult): void {
  if (result.teamId !== useAuthStore.getState().currentTeamId) return
  void queryClient.invalidateQueries({ queryKey: [CHAT_EXECUTIONS_QUERY_KEY, result.chatId] })
}

export function handleIngestionResult(result: IngestionResult): void {
  const teamId = useAuthStore.getState().currentTeamId
  const repoId = result.event.repositoryId
  if (!teamId || !repoId) return
  void queryClient.invalidateQueries({ queryKey: ['batch-history', teamId, repoId] })
  void queryClient.invalidateQueries({
    queryKey: ['batch-stats', teamId, repoId, result.event.batchId],
  })
  for (const wikiKey of ['wiki-page', 'wiki-children', 'wiki-pages', 'wiki-tree'] as const) {
    void queryClient.invalidateQueries({ queryKey: [wikiKey, teamId, repoId] })
  }
}

let lastTeamId: null | string = null

function syncTeamSubscription() {
  const currentTeamId = useAuthStore.getState().currentTeamId
  if (currentTeamId !== lastTeamId) {
    if (lastTeamId) {
      useWebSocketStore.getState().unsubscribe(lastTeamId)
    }
    lastTeamId = currentTeamId
    if (lastTeamId) {
      useWebSocketStore.getState().subscribe(lastTeamId)
    }
  }
}

syncTeamSubscription()

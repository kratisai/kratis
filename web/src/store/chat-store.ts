import { toast } from 'sonner'
import { create } from 'zustand'

import type { WorkingItem } from '@/types/telemetry-types'
import type {
  ChatErrorResult,
  CompleteResult,
  JsonRpcError,
  MessageChunkResult,
  MessageResult,
  TelemetryEvent,
  TelemetryResult,
} from '@/types/websocket-types'

import { useAuthStore } from '@/store/auth-store'
import { useCanvasStore } from '@/store/canvas-store'
import {
  isThoughtEvent,
  isToolCompleteEvent,
  isToolErrorEvent,
  isToolStartEvent,
} from '@/store/telemetry-store'
import { useUIStore } from '@/store/ui-store'
import { useWebSocketStore } from '@/store/websocket-store'
const STREAM_FLUSH_INTERVAL_MS = 50

// A single ordered sequence of chat turns and working blocks, in the order they
// actually occurred - no separate keyed collection to keep in sync by ID.
export type ChatMessage = TextChatMessage | WorkingChatMessage

// A regular user/assistant/system chat bubble.
export interface TextChatMessage {
  content: string
  id: string
  isError?: boolean
  role: 'assistant' | 'system' | 'user'
  timestamp: Date
}

// A single "Agent working..." block, tracking the agent's telemetry (thoughts and
// tool calls) for one ReACT loop turn. Created lazily on the first telemetry event
// received after a user message (see handleTelemetryEvent) - never pre-created when
// the user message is sent, so it only ever appears once there is something to show.
export interface WorkingChatMessage {
  endTime: null | number
  id: string
  isStreaming: boolean
  items: WorkingItem[]
  role: 'working'
  startTime: null | number
  timestamp: Date
}

interface ChatState {
  addMessage: (chatId: string, message: ChatMessage) => void
  clearChats: () => void
  clearMessages: (chatId: string) => void

  currentChatId: null | string
  finishWorking: (chatId: string) => void
  flushStreamingUpdates: () => void
  handleChatErrorResult: (chatErrorResult: ChatErrorResult) => void
  handleCompleteResult: (completeResult: CompleteResult) => void

  handleDisconnect: () => void
  handleErrorResponse: (error: JsonRpcError) => void
  handleMessageChunkResult: (messageChunkResult: MessageChunkResult) => void
  handleMessageResult: (messageResult: MessageResult) => void
  handleTelemetryEvent: (chatId: string, telemetryResult: TelemetryResult) => void
  messages: Record<string, ChatMessage[]>
  sendMessage: (chatId: string, message: string) => void
  subscribeChat: (chatId: string) => void
  subscribedChatIds: Set<string>
  unsubscribeChat: (chatId: string) => void
}

// Module-level accumulators for streaming and throttling
const currentStreamingContent = new Map<string, string>()
const pendingFlushTimers = new Map<string, ReturnType<typeof setTimeout>>()
const pendingMessageIds = new Set<string>()

// Track active chat send operations (chat IDs with in-progress sends)
const activeChatSends = new Set<string>()

let thoughtCounter = 0

// Applies a single telemetry event to a working message's items array, returning a
// new array (or the same array reference if the event didn't match anything, e.g. a
// ToolComplete/ToolError for a taskId we never saw a ToolStart for).
function applyTelemetryEvent(items: WorkingItem[], event: TelemetryEvent): WorkingItem[] {
  if (isToolStartEvent(event)) {
    const newTool: WorkingItem = {
      status: 'running',
      taskId: event.taskId,
      thought: event.thought,
      toolName: event.toolName,
      type: 'tool',
    }
    return [...items, newTool]
  }

  if (isToolErrorEvent(event)) {
    const itemIndex = items.findIndex(
      (item) => item.type === 'tool' && item.taskId === event.taskId,
    )
    if (itemIndex === -1) return items
    const updatedItems = [...items]
    const existingTool = updatedItems[itemIndex]
    if (existingTool.type === 'tool') {
      updatedItems[itemIndex] = {
        ...existingTool,
        errorMessage: event.error,
        status: 'error',
      }
    }
    return updatedItems
  }

  if (isToolCompleteEvent(event)) {
    const itemIndex = items.findIndex(
      (item) => item.type === 'tool' && item.taskId === event.taskId,
    )
    if (itemIndex === -1) return items
    const updatedItems = [...items]
    const existingTool = updatedItems[itemIndex]
    if (existingTool.type === 'tool') {
      updatedItems[itemIndex] = {
        ...existingTool,
        status: 'complete',
      }
    }
    return updatedItems
  }

  if (isThoughtEvent(event)) {
    const last = items.at(-1)
    if (last && last.type === 'thought') {
      const updatedItems = [...items]
      updatedItems[updatedItems.length - 1] = {
        ...last,
        text: last.text + event.text,
      }
      return updatedItems
    }
    thoughtCounter++
    const newThought: WorkingItem = {
      id: `thought-${thoughtCounter}-${Date.now()}`,
      text: event.text,
      type: 'thought',
    }
    return [...items, newThought]
  }

  return items
}

// Replaces the content of the text message with the given id, leaving working
// messages (which have no content field) untouched.
function updateMessageContent(
  messages: ChatMessage[],
  messageId: string,
  content: string,
): ChatMessage[] {
  return messages.map((msg) =>
    msg.id === messageId && msg.role !== 'working' ? { ...msg, content } : msg,
  )
}

export const useChatStore = create<ChatState>((set, get) => ({
  addMessage: (chatId: string, message: ChatMessage) => {
    set((state) => {
      const existing = state.messages[chatId] ?? []
      if (existing.some((m) => m.id === message.id)) {
        return state
      }
      return {
        messages: {
          ...state.messages,
          [chatId]: [...existing, message],
        },
      }
    })
  },
  clearChats: () => {
    set({
      currentChatId: null,
      messages: {},
      subscribedChatIds: new Set<string>(),
    })

    currentStreamingContent.clear()
    pendingMessageIds.clear()
    activeChatSends.clear()
    thoughtCounter = 0

    for (const timer of pendingFlushTimers.values()) {
      clearTimeout(timer)
    }
    pendingFlushTimers.clear()

    useCanvasStore.getState().clearCanvases()
  },
  clearMessages: (chatId: string) => {
    set((state) => {
      const newMessages = { ...state.messages }
      delete newMessages[chatId]
      return { messages: newMessages }
    })
  },
  currentChatId: null,

  finishWorking: (chatId: string) => {
    set((state) => {
      const chatMessages = state.messages[chatId] ?? []
      const openIndex = chatMessages.findIndex((m) => m.role === 'working' && m.isStreaming)
      if (openIndex === -1) return state

      const existing = chatMessages[openIndex]
      if (existing.role !== 'working') return state

      const updatedMessages = [...chatMessages]
      updatedMessages[openIndex] = { ...existing, endTime: Date.now(), isStreaming: false }
      return {
        messages: {
          ...state.messages,
          [chatId]: updatedMessages,
        },
      }
    })
  },

  flushStreamingUpdates: () => {
    for (const [messageId, timer] of pendingFlushTimers.entries()) {
      clearTimeout(timer)
      pendingFlushTimers.delete(messageId)

      const accumulatedContent = currentStreamingContent.get(messageId) || ''
      set((state) => {
        const updatedMessages: Record<string, ChatMessage[]> = {}
        for (const [chatId, chatMessages] of Object.entries(state.messages)) {
          updatedMessages[chatId] = updateMessageContent(
            chatMessages,
            messageId,
            accumulatedContent,
          )
        }
        return { messages: updatedMessages }
      })
    }
  },

  handleChatErrorResult: (chatErrorResult: ChatErrorResult) => {
    const { chatId, message, messageId } = chatErrorResult
    const activeChatId = chatId || get().currentChatId
    if (!activeChatId) return

    const pendingTimer = pendingFlushTimers.get(messageId)
    if (pendingTimer) {
      clearTimeout(pendingTimer)
      pendingFlushTimers.delete(messageId)
    }
    const accumulatedContent = currentStreamingContent.get(messageId)
    if (accumulatedContent) {
      set((state) => {
        const chatMessages = state.messages[activeChatId] ?? []
        return {
          messages: {
            ...state.messages,
            [activeChatId]: updateMessageContent(chatMessages, messageId, accumulatedContent),
          },
        }
      })
      currentStreamingContent.delete(messageId)
    }
    pendingMessageIds.delete(messageId)

    get().addMessage(activeChatId, {
      content: message,
      id: `error-${messageId}`,
      isError: true,
      role: 'assistant',
      timestamp: new Date(),
    })

    activeChatSends.delete(activeChatId)
    get().finishWorking(activeChatId)
  },

  handleCompleteResult: (completeResult: CompleteResult) => {
    const { chatId, messageId } = completeResult

    if (messageId) {
      // Chat stream complete - flush pending content and close out the working block
      const activeChatId = chatId || get().currentChatId
      if (activeChatId) {
        get().finishWorking(activeChatId)
      }

      const pendingTimer = pendingFlushTimers.get(messageId)
      if (pendingTimer) {
        clearTimeout(pendingTimer)
        pendingFlushTimers.delete(messageId)
      }

      if (currentStreamingContent.has(messageId)) {
        const completeContent = currentStreamingContent.get(messageId) || ''

        if (activeChatId) {
          set((state) => {
            const chatMessages = state.messages[activeChatId] ?? []
            return {
              messages: {
                ...state.messages,
                [activeChatId]: updateMessageContent(chatMessages, messageId, completeContent),
              },
            }
          })
        }
        currentStreamingContent.delete(messageId)
      }
      pendingMessageIds.delete(messageId)
    }

    if (chatId) {
      // Chat load complete marker (history replay from chat.subscribe)
      set((state) => ({
        currentChatId: chatId,
        messages: {
          ...state.messages,
          [chatId]: state.messages[chatId] ?? [],
        },
      }))
    }
  },

  handleDisconnect: () => {
    // Clear all active operations and streaming state
    activeChatSends.clear()
    thoughtCounter = 0

    // Flush any pending streaming content
    for (const [messageId, timer] of pendingFlushTimers.entries()) {
      clearTimeout(timer)
      pendingFlushTimers.delete(messageId)

      const accumulatedContent = currentStreamingContent.get(messageId) || ''
      set((state) => {
        const updatedMessages: Record<string, ChatMessage[]> = {}
        for (const [chatId, chatMessages] of Object.entries(state.messages)) {
          updatedMessages[chatId] = updateMessageContent(
            chatMessages,
            messageId,
            accumulatedContent,
          )
        }
        return { messages: updatedMessages }
      })
    }

    // Clear streaming accumulators
    currentStreamingContent.clear()
    pendingMessageIds.clear()

    // Clear canvas
    useCanvasStore.getState().clearCanvases()
  },

  handleErrorResponse: (error: JsonRpcError) => {
    const currentChatId = get().currentChatId
    if (!currentChatId) return

    // Flush any pending streaming content first
    for (const [messageId, timer] of pendingFlushTimers.entries()) {
      clearTimeout(timer)
      pendingFlushTimers.delete(messageId)
      const accumulatedContent = currentStreamingContent.get(messageId) || ''
      if (accumulatedContent) {
        set((state) => {
          const chatMessages = state.messages[currentChatId] ?? []
          return {
            messages: {
              ...state.messages,
              [currentChatId]: updateMessageContent(chatMessages, messageId, accumulatedContent),
            },
          }
        })
      }
    }
    currentStreamingContent.clear()
    pendingMessageIds.clear()

    // Add error message to chat
    const errorMessage: ChatMessage = {
      content: error.message,
      id: `error-${Date.now()}`,
      isError: true,
      role: 'assistant' as const,
      timestamp: new Date(),
    }
    get().addMessage(currentChatId, errorMessage)

    // Clear active operations and close out the working block, if any
    activeChatSends.clear()
    get().finishWorking(currentChatId)
  },

  handleMessageChunkResult: (messageChunkResult: MessageChunkResult) => {
    const { chatId, content, messageId, timestamp } = messageChunkResult
    const activeChatId = chatId || get().currentChatId
    if (!activeChatId) return

    const existingContent = currentStreamingContent.get(messageId) || ''
    const newContent = existingContent + content
    currentStreamingContent.set(messageId, newContent)

    if (!pendingMessageIds.has(messageId)) {
      pendingMessageIds.add(messageId)
      const streamingMessage: ChatMessage = {
        content: newContent,
        id: messageId,
        role: 'assistant',
        timestamp: timestamp ? new Date(timestamp) : new Date(),
      }
      set((state) => {
        const chatMsgs = state.messages[activeChatId] ?? []
        if (chatMsgs.some((m) => m.id === messageId)) {
          return state
        }
        return {
          messages: {
            ...state.messages,
            [activeChatId]: [...chatMsgs, streamingMessage],
          },
        }
      })
    }

    const existingTimer = pendingFlushTimers.get(messageId)
    if (existingTimer) {
      clearTimeout(existingTimer)
    }

    const timer = setTimeout(() => {
      pendingFlushTimers.delete(messageId)
      const accumulatedContent = currentStreamingContent.get(messageId) || ''

      set((state) => {
        const chatMessages = state.messages[activeChatId] ?? []
        return {
          messages: {
            ...state.messages,
            [activeChatId]: updateMessageContent(chatMessages, messageId, accumulatedContent),
          },
        }
      })
    }, STREAM_FLUSH_INTERVAL_MS)
    pendingFlushTimers.set(messageId, timer)
  },

  handleMessageResult: (messageResult: MessageResult) => {
    const { chatId, content, messageId, role, timestamp } = messageResult
    if (!chatId) return

    const msg: ChatMessage = {
      content,
      id: messageId,
      role: role,
      timestamp: timestamp ? new Date(timestamp) : new Date(),
    }

    set((state) => {
      const existingMessages = state.messages[chatId] ?? []
      const index = existingMessages.findIndex((m) => m.id === messageId)
      let updatedMessages: ChatMessage[]
      if (index >= 0) {
        updatedMessages = [...existingMessages]
        updatedMessages[index] = msg
      } else {
        updatedMessages = [...existingMessages, msg]
      }
      return {
        messages: {
          ...state.messages,
          [chatId]: updatedMessages,
        },
      }
    })
  },

  handleTelemetryEvent: (chatId: string, telemetryResult: TelemetryResult) => {
    const event = telemetryResult.event

    set((state) => {
      const chatMessages = state.messages[chatId] ?? []
      // Find the currently open working block for this chat, if any (isStreaming still
      // true - i.e. not yet finished by handleCompleteResult/handleErrorResponse).
      const openIndex = chatMessages.findIndex((m) => m.role === 'working' && m.isStreaming)

      if (openIndex !== -1) {
        const existing = chatMessages[openIndex]
        if (existing.role !== 'working') return state

        const updatedMessages = [...chatMessages]
        updatedMessages[openIndex] = {
          ...existing,
          items: applyTelemetryEvent(existing.items, event),
        }
        return {
          messages: {
            ...state.messages,
            [chatId]: updatedMessages,
          },
        }
      }

      // No open working block - this is the first telemetry event for a new turn, so
      // lazily create the working message now (never pre-created on user message send).
      const newWorking: WorkingChatMessage = {
        endTime: null,
        id: `working-${Date.now()}`,
        isStreaming: true,
        items: applyTelemetryEvent([], event),
        role: 'working',
        startTime: Date.now(),
        timestamp: new Date(),
      }
      return {
        messages: {
          ...state.messages,
          [chatId]: [...chatMessages, newWorking],
        },
      }
    })
  },

  messages: {},

  sendMessage: (chatId: string, message: string) => {
    const { selectedModelName, selectedProviderId } = useUIStore.getState()
    const teamId = useAuthStore.getState().currentTeamId
    if (!teamId) {
      toast.error('No team selected')
      return
    }
    if (!selectedProviderId) {
      toast.error('No model provider selected')
      return
    }
    if (!selectedModelName) {
      toast.error('No model selected')
      return
    }
    if (!chatId) {
      toast.error('No chat selected')
      return
    }

    const params = {
      chatId,
      message,
      modelName: selectedModelName,
      providerId: selectedProviderId,
      teamId,
    }

    activeChatSends.add(chatId)
    useWebSocketStore.getState().send('chat.send', params)
  },

  subscribeChat: (chatId: string) => {
    const subscribed = get().subscribedChatIds
    if (subscribed.has(chatId)) {
      set({ currentChatId: chatId })
      return
    }

    const nextSubscribed = new Set(subscribed)
    nextSubscribed.add(chatId)

    set({ currentChatId: chatId, subscribedChatIds: nextSubscribed })

    const teamId = useAuthStore.getState().currentTeamId
    useWebSocketStore.getState().send('chat.subscribe', { chatId, teamId: teamId || undefined })
  },

  subscribedChatIds: new Set<string>(),

  unsubscribeChat: (chatId: string) => {
    const subscribed = get().subscribedChatIds
    if (!subscribed.has(chatId)) return

    const nextSubscribed = new Set(subscribed)
    nextSubscribed.delete(chatId)

    set((state) => ({
      currentChatId: state.currentChatId === chatId ? null : state.currentChatId,
      subscribedChatIds: nextSubscribed,
    }))

    useWebSocketStore.getState().send('chat.unsubscribe', { chatId })
  },
}))

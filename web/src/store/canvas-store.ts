import { create } from 'zustand'

import type {
  CanvasCommitEvent,
  CanvasCreateEvent,
  CanvasDeleteEvent,
  CanvasDocument,
  CanvasErrorEvent,
  CanvasEvent,
  CanvasUpdateEvent,
} from '@/types/canvas-types'
import type { CanvasResult } from '@/types/websocket-types'

interface CanvasState {
  canvases: Record<string, CanvasDocument[]> // chatId -> documents

  clearCanvases: () => void
  clearChatCanvases: (chatId: string) => void
  handleCanvasEvent: (canvasResult: CanvasResult) => void
  removeCanvasDocument: (chatId: string, documentId: string) => void
}

export function isCanvasCommitEvent(event: CanvasEvent): event is CanvasCommitEvent {
  return 'version' in event && !('content' in event) && !('title' in event)
}

// Type guards for CanvasEvent discrimination
// Create events have title+content but NO version
// Update events have title+content+version
export function isCanvasCreateEvent(event: CanvasEvent): event is CanvasCreateEvent {
  return 'title' in event && 'content' in event && !('version' in event)
}

export function isCanvasDeleteEvent(event: CanvasEvent): event is CanvasDeleteEvent {
  return (
    'chatId' in event &&
    'documentId' in event &&
    !('title' in event) &&
    !('content' in event) &&
    !('version' in event) &&
    !('errorMessage' in event)
  )
}

export function isCanvasErrorEvent(event: CanvasEvent): event is CanvasErrorEvent {
  return 'errorMessage' in event
}

export function isCanvasUpdateEvent(event: CanvasEvent): event is CanvasUpdateEvent {
  return 'content' in event && 'version' in event && 'title' in event
}

export const useCanvasStore = create<CanvasState>((set) => ({
  canvases: {},

  clearCanvases: () => {
    set({ canvases: {} })
  },

  clearChatCanvases: (chatId: string) => {
    set((state) => {
      const newCanvases = { ...state.canvases }
      delete newCanvases[chatId]
      return { canvases: newCanvases }
    })
  },

  handleCanvasEvent: (canvasResult: CanvasResult) => {
    const event = canvasResult.event

    if (isCanvasCreateEvent(event)) {
      set((state) => {
        const chatId = event.chatId
        const chatDocs = state.canvases[chatId] ?? []
        const existingIndex = chatDocs.findIndex((doc) => doc.documentId === event.documentId)

        const newDoc: CanvasDocument = {
          canvasType: event.canvasType,
          chatId: event.chatId,
          content: event.content,
          documentId: event.documentId,
          isNewRepo: event.isNewRepo,
          repoLabel: event.repoLabel,
          title: event.title,
          version: 1,
        }

        let newDocs: CanvasDocument[]
        if (existingIndex >= 0) {
          newDocs = [...chatDocs]
          newDocs[existingIndex] = newDoc
        } else {
          newDocs = [...chatDocs, newDoc]
        }

        return {
          canvases: {
            ...state.canvases,
            [chatId]: newDocs,
          },
        }
      })
    } else if (isCanvasUpdateEvent(event)) {
      set((state) => {
        const chatId = event.chatId
        const chatDocs = state.canvases[chatId] ?? []
        const docIndex = chatDocs.findIndex((doc) => doc.documentId === event.documentId)

        const newDocs = [...chatDocs]
        if (docIndex >= 0) {
          // Update existing document
          newDocs[docIndex] = {
            ...newDocs[docIndex],
            canvasType: event.canvasType,
            content: event.content,
            isNewRepo: event.isNewRepo,
            repoLabel: event.repoLabel,
            title: event.title,
            version: event.version,
          }
        } else {
          // Document not in store yet (e.g., after page refresh) - create it
          newDocs.push({
            canvasType: event.canvasType,
            chatId: event.chatId,
            content: event.content,
            documentId: event.documentId,
            isNewRepo: event.isNewRepo,
            repoLabel: event.repoLabel,
            title: event.title,
            version: event.version,
          })
        }
        return {
          canvases: {
            ...state.canvases,
            [chatId]: newDocs,
          },
        }
      })
    } else if (isCanvasCommitEvent(event)) {
      set((state) => {
        const chatId = event.chatId
        const chatDocs = state.canvases[chatId] ?? []
        const docIndex = chatDocs.findIndex((doc) => doc.documentId === event.documentId)

        if (docIndex >= 0) {
          const newDocs = [...chatDocs]
          newDocs[docIndex] = {
            ...newDocs[docIndex],
            version: event.version,
          }
          return {
            canvases: {
              ...state.canvases,
              [chatId]: newDocs,
            },
          }
        }
        return state
      })
    } else if (isCanvasErrorEvent(event)) {
      console.error('Canvas error:', event.errorMessage)
      // Could show a toast notification here
    } else if (isCanvasDeleteEvent(event)) {
      set((state) => {
        const chatId = event.chatId
        const chatDocs = state.canvases[chatId] ?? []
        if (!chatDocs.some((doc) => doc.documentId === event.documentId)) return state
        const newDocs = chatDocs.filter((doc) => doc.documentId !== event.documentId)
        const newCanvases = { ...state.canvases }
        if (newDocs.length === 0) {
          delete newCanvases[chatId]
        } else {
          newCanvases[chatId] = newDocs
        }
        return { canvases: newCanvases }
      })
    }
  },

  removeCanvasDocument: (chatId: string, documentId: string) => {
    set((state) => {
      const chatDocs = state.canvases[chatId] ?? []
      const newDocs = chatDocs.filter((doc) => doc.documentId !== documentId)
      if (newDocs.length === chatDocs.length) return state
      const newCanvases = { ...state.canvases }
      if (newDocs.length === 0) {
        delete newCanvases[chatId]
      } else {
        newCanvases[chatId] = newDocs
      }
      return { canvases: newCanvases }
    })
  },
}))

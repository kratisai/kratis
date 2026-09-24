import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type {
  CanvasCreateEvent,
  CanvasDeleteEvent,
  CanvasEvent,
  CanvasUpdateEvent,
} from '@/types/canvas-types'
import type { CanvasResult } from '@/types/websocket-types'

import {
  isCanvasCommitEvent,
  isCanvasCreateEvent,
  isCanvasDeleteEvent,
  isCanvasErrorEvent,
  isCanvasUpdateEvent,
  useCanvasStore,
} from '@/store/canvas-store'

// Helper to create a CanvasResult with proper typing
function createCanvasResult(event: CanvasEvent): CanvasResult {
  return {
    event,
    type: 'canvas',
  }
}

describe('canvas-store type guards', () => {
  it('isCanvasCreateEvent returns true for create events', () => {
    const event: CanvasCreateEvent = {
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Hello',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Test Doc',
    }
    expect(isCanvasCreateEvent(event)).toBe(true)
  })

  it('isCanvasCreateEvent returns false for non-create events', () => {
    const event = {
      chatId: 'session-1',
      documentId: 'doc-1',
      version: 2,
    }
    expect(isCanvasCreateEvent(event)).toBe(false)
  })

  it('isCanvasUpdateEvent returns true for update events', () => {
    const event: CanvasUpdateEvent = {
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Updated',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Test Doc',
      version: 2,
    }
    expect(isCanvasUpdateEvent(event)).toBe(true)
  })

  it('isCanvasUpdateEvent returns false for create events (no version)', () => {
    const event: CanvasCreateEvent = {
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Hello',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Test Doc',
    }
    expect(isCanvasUpdateEvent(event)).toBe(false)
  })

  it('isCanvasCommitEvent returns true for commit events', () => {
    const event = {
      chatId: 'session-1',
      documentId: 'doc-1',
      version: 3,
    }
    expect(isCanvasCommitEvent(event)).toBe(true)
  })

  it('isCanvasCommitEvent returns false for events with content', () => {
    const event = {
      chatId: 'session-1',
      content: '# Hello',
      documentId: 'doc-1',
      version: 1,
    }
    expect(isCanvasCommitEvent(event)).toBe(false)
  })

  it('isCanvasDeleteEvent returns true for delete events', () => {
    const event: CanvasDeleteEvent = {
      chatId: 'session-1',
      documentId: 'doc-1',
    }
    expect(isCanvasDeleteEvent(event)).toBe(true)
  })

  it('isCanvasDeleteEvent returns false for commit events (version present)', () => {
    const event = {
      chatId: 'session-1',
      documentId: 'doc-1',
      version: 2,
    }
    expect(isCanvasDeleteEvent(event)).toBe(false)
  })

  it('isCanvasDeleteEvent returns false for error events (errorMessage present)', () => {
    const event = {
      chatId: 'session-1',
      documentId: 'doc-1',
      errorMessage: 'Failed to load canvas',
    }
    expect(isCanvasDeleteEvent(event)).toBe(false)
  })

  it('isCanvasErrorEvent returns true for error events', () => {
    const event = {
      chatId: 'session-1',
      documentId: 'doc-1',
      errorMessage: 'Something went wrong',
    }
    expect(isCanvasErrorEvent(event)).toBe(true)
  })

  it('isCanvasErrorEvent returns false for non-error events', () => {
    const event: CanvasCreateEvent = {
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Hello',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Test Doc',
    }
    expect(isCanvasErrorEvent(event)).toBe(false)
  })
})

describe('useCanvasStore', () => {
  beforeEach(() => {
    useCanvasStore.setState({
      canvases: {},
      unreadCanvasDocIds: {},
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('should have initial state', () => {
    const state = useCanvasStore.getState()
    expect(state.canvases).toEqual({})
    expect(state.unreadCanvasDocIds).toEqual({})
  })

  it('clearCanvases should reset state', () => {
    // First set some state
    useCanvasStore.setState({
      canvases: {
        'session-1': [
          {
            canvasType: 'DOCUMENT',
            chatId: 'session-1',
            content: '# Hello',
            documentId: 'doc-1',
            isNewRepo: false,
            title: 'Test Doc',
            version: 1,
          },
        ],
      },
    })

    useCanvasStore.getState().clearCanvases()
    const state = useCanvasStore.getState()
    expect(state.canvases).toEqual({})
  })

  it('clearChatCanvases should remove canvases for a specific chat', () => {
    // Set up state with multiple sessions
    useCanvasStore.setState({
      canvases: {
        'session-1': [
          {
            canvasType: 'DOCUMENT',
            chatId: 'session-1',
            content: '# Session 1',
            documentId: 'doc-1',
            isNewRepo: false,
            title: 'Session 1 Doc',
            version: 1,
          },
        ],
        'session-2': [
          {
            canvasType: 'DOCUMENT',
            chatId: 'session-2',
            content: '# Session 2',
            documentId: 'doc-2',
            isNewRepo: false,
            title: 'Session 2 Doc',
            version: 1,
          },
        ],
      },
    })

    useCanvasStore.getState().clearChatCanvases('session-1')
    const state = useCanvasStore.getState()
    expect(state.canvases['session-1']).toBeUndefined()
    expect(state.canvases['session-2']).toHaveLength(1)
  })

  it('handleCanvasEvent should handle create event', () => {
    const canvasResult = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Hello World',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'My Document',
    })

    useCanvasStore.getState().handleCanvasEvent(canvasResult)

    const state = useCanvasStore.getState()
    expect(state.canvases['session-1']).toHaveLength(1)
    expect(state.canvases['session-1'][0]).toEqual({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Hello World',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'My Document',
      version: 1,
    })
  })

  it('handleCanvasEvent should handle update event', () => {
    // First create a document
    const createResult = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Initial',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Test Doc',
    })
    useCanvasStore.getState().handleCanvasEvent(createResult)

    // Then update it (update events must include title)
    const updateResult = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Updated Content',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Test Doc',
      version: 2,
    })
    useCanvasStore.getState().handleCanvasEvent(updateResult)

    const state = useCanvasStore.getState()
    expect(state.canvases['session-1'][0].content).toBe('# Updated Content')
    expect(state.canvases['session-1'][0].version).toBe(2)
  })

  it('handleCanvasEvent should handle commit event', () => {
    // First create a document
    const createResult = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Initial',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Test Doc',
    })
    useCanvasStore.getState().handleCanvasEvent(createResult)

    // Then commit it
    const commitResult = createCanvasResult({
      chatId: 'session-1',
      documentId: 'doc-1',
      version: 3,
    })
    useCanvasStore.getState().handleCanvasEvent(commitResult)

    const state = useCanvasStore.getState()
    expect(state.canvases['session-1'][0].version).toBe(3)
  })

  it('handleCanvasEvent should handle error event', () => {
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    const errorResult = createCanvasResult({
      chatId: 'session-1',
      documentId: 'doc-1',
      errorMessage: 'Failed to load canvas',
    })
    useCanvasStore.getState().handleCanvasEvent(errorResult)

    expect(consoleSpy).toHaveBeenCalledWith('Canvas error:', 'Failed to load canvas')
    consoleSpy.mockRestore()
  })

  it('handleCanvasEvent should add new document to existing session', () => {
    // Create first document
    const createResult1 = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Doc 1',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Document 1',
    })
    useCanvasStore.getState().handleCanvasEvent(createResult1)

    // Create second document
    const createResult2 = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Doc 2',
      documentId: 'doc-2',
      isNewRepo: false,
      title: 'Document 2',
    })
    useCanvasStore.getState().handleCanvasEvent(createResult2)

    const state = useCanvasStore.getState()
    expect(state.canvases['session-1']).toHaveLength(2)
    expect(state.canvases['session-1'][1].documentId).toBe('doc-2')
  })

  it('handleCanvasEvent should update existing document on create (upsert)', () => {
    // Create first document
    const createResult1 = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Initial',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Original Title',
    })
    useCanvasStore.getState().handleCanvasEvent(createResult1)

    // Create same document again (should update)
    const createResult2 = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Updated',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'New Title',
    })
    useCanvasStore.getState().handleCanvasEvent(createResult2)

    const state = useCanvasStore.getState()
    expect(state.canvases['session-1']).toHaveLength(1)
    expect(state.canvases['session-1'][0].title).toBe('New Title')
    expect(state.canvases['session-1'][0].content).toBe('# Updated')
  })

  it('handleCanvasEvent should handle update for non-existent document by creating it', () => {
    const updateResult = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Orphan Update',
      documentId: 'non-existent-doc',
      isNewRepo: false,
      title: 'non-existent-doc',
      version: 1,
    })
    useCanvasStore.getState().handleCanvasEvent(updateResult)

    const state = useCanvasStore.getState()
    // Update event for non-existent doc should create the document
    expect(state.canvases['session-1']).toHaveLength(1)
    expect(state.canvases['session-1'][0].documentId).toBe('non-existent-doc')
    expect(state.canvases['session-1'][0].content).toBe('# Orphan Update')
    expect(state.canvases['session-1'][0].title).toBe('non-existent-doc')
    expect(state.canvases['session-1'][0].version).toBe(1)
  })

  it('handleCanvasEvent should handle commit for non-existent document gracefully', () => {
    const commitResult = createCanvasResult({
      chatId: 'session-1',
      documentId: 'non-existent-doc',
      version: 1,
    })
    useCanvasStore.getState().handleCanvasEvent(commitResult)

    const state = useCanvasStore.getState()
    expect(state.canvases['session-1']).toBeUndefined()
  })

  it('handleCanvasEvent should handle multiple sessions', () => {
    const createResult1 = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Session 1 Doc',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Session 1 Document',
    })
    useCanvasStore.getState().handleCanvasEvent(createResult1)

    const createResult2 = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-2',
      content: '# Session 2 Doc',
      documentId: 'doc-2',
      isNewRepo: false,
      title: 'Session 2 Document',
    })
    useCanvasStore.getState().handleCanvasEvent(createResult2)

    const state = useCanvasStore.getState()
    expect(state.canvases['session-1']).toHaveLength(1)
    expect(state.canvases['session-2']).toHaveLength(1)
    expect(state.canvases['session-1'][0].chatId).toBe('session-1')
    expect(state.canvases['session-2'][0].chatId).toBe('session-2')
  })

  it('handleCanvasEvent should handle delete event by removing the document', () => {
    const create1 = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Doc 1',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Document 1',
    })
    const create2 = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Doc 2',
      documentId: 'doc-2',
      isNewRepo: false,
      title: 'Document 2',
    })
    useCanvasStore.getState().handleCanvasEvent(create1)
    useCanvasStore.getState().handleCanvasEvent(create2)

    const deleteResult = createCanvasResult({
      chatId: 'session-1',
      documentId: 'doc-1',
    })
    useCanvasStore.getState().handleCanvasEvent(deleteResult)

    const state = useCanvasStore.getState()
    expect(state.canvases['session-1']).toHaveLength(1)
    expect(state.canvases['session-1'][0].documentId).toBe('doc-2')
  })

  it('handleCanvasEvent should remove the chat entry when the last document is deleted', () => {
    const create1 = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Doc 1',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Document 1',
    })
    useCanvasStore.getState().handleCanvasEvent(create1)

    const deleteResult = createCanvasResult({
      chatId: 'session-1',
      documentId: 'doc-1',
    })
    useCanvasStore.getState().handleCanvasEvent(deleteResult)

    expect(useCanvasStore.getState().canvases['session-1']).toBeUndefined()
  })

  it('handleCanvasEvent should ignore delete events for unknown documents', () => {
    const create1 = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Doc 1',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Document 1',
    })
    useCanvasStore.getState().handleCanvasEvent(create1)

    const deleteResult = createCanvasResult({
      chatId: 'session-1',
      documentId: 'doc-missing',
    })
    useCanvasStore.getState().handleCanvasEvent(deleteResult)

    expect(useCanvasStore.getState().canvases['session-1']).toHaveLength(1)
  })

  it('removeCanvasDocument should remove a specific document', () => {
    const create1 = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Doc 1',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Document 1',
    })
    const create2 = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Doc 2',
      documentId: 'doc-2',
      isNewRepo: false,
      title: 'Document 2',
    })
    useCanvasStore.getState().handleCanvasEvent(create1)
    useCanvasStore.getState().handleCanvasEvent(create2)

    useCanvasStore.getState().removeCanvasDocument('session-1', 'doc-2')

    const state = useCanvasStore.getState()
    expect(state.canvases['session-1']).toHaveLength(1)
    expect(state.canvases['session-1'][0].documentId).toBe('doc-1')
  })

  it('removeCanvasDocument should clear the chat entry when the last document is removed', () => {
    const create1 = createCanvasResult({
      canvasType: 'DOCUMENT',
      chatId: 'session-1',
      content: '# Doc 1',
      documentId: 'doc-1',
      isNewRepo: false,
      title: 'Document 1',
    })
    useCanvasStore.getState().handleCanvasEvent(create1)

    useCanvasStore.getState().removeCanvasDocument('session-1', 'doc-1')

    expect(useCanvasStore.getState().canvases['session-1']).toBeUndefined()
  })

  describe('unread activity tracking', () => {
    it('handleCanvasEvent should add document ID to unreadCanvasDocIds on create event', () => {
      const createResult = createCanvasResult({
        canvasType: 'DOCUMENT',
        chatId: 'session-1',
        content: '# New Doc',
        documentId: 'doc-1',
        isNewRepo: false,
        title: 'New Document',
      })
      useCanvasStore.getState().handleCanvasEvent(createResult)

      expect(useCanvasStore.getState().unreadCanvasDocIds['session-1']).toEqual(['doc-1'])
    })

    it('handleCanvasEvent should add document ID to unreadCanvasDocIds on update event', () => {
      // Create first without unread or existing
      useCanvasStore.setState({
        canvases: {
          'session-1': [
            {
              canvasType: 'DOCUMENT',
              chatId: 'session-1',
              content: '# Initial',
              documentId: 'doc-1',
              isNewRepo: false,
              title: 'Doc 1',
              version: 1,
            },
          ],
        },
        unreadCanvasDocIds: {},
      })

      const updateResult = createCanvasResult({
        canvasType: 'DOCUMENT',
        chatId: 'session-1',
        content: '# Updated content',
        documentId: 'doc-1',
        isNewRepo: false,
        title: 'Doc 1',
        version: 2,
      })
      useCanvasStore.getState().handleCanvasEvent(updateResult)

      expect(useCanvasStore.getState().unreadCanvasDocIds['session-1']).toEqual(['doc-1'])
    })

    it('does not duplicate documentId in unreadCanvasDocIds on multiple events', () => {
      const createResult = createCanvasResult({
        canvasType: 'DOCUMENT',
        chatId: 'session-1',
        content: '# Content 1',
        documentId: 'doc-1',
        isNewRepo: false,
        title: 'Doc 1',
      })
      const updateResult = createCanvasResult({
        canvasType: 'DOCUMENT',
        chatId: 'session-1',
        content: '# Content 2',
        documentId: 'doc-1',
        isNewRepo: false,
        title: 'Doc 1',
        version: 2,
      })
      useCanvasStore.getState().handleCanvasEvent(createResult)
      useCanvasStore.getState().handleCanvasEvent(updateResult)

      expect(useCanvasStore.getState().unreadCanvasDocIds['session-1']).toEqual(['doc-1'])
    })

    it('clearCanvasActivity removes a specific document ID from unreadCanvasDocIds', () => {
      useCanvasStore.setState({
        unreadCanvasDocIds: {
          'session-1': ['doc-1', 'doc-2'],
        },
      })

      useCanvasStore.getState().clearCanvasActivity('session-1', 'doc-1')

      expect(useCanvasStore.getState().unreadCanvasDocIds['session-1']).toEqual(['doc-2'])
    })

    it('clearCanvasActivity clears all document IDs for chat when documentId is omitted', () => {
      useCanvasStore.setState({
        unreadCanvasDocIds: {
          'session-1': ['doc-1', 'doc-2'],
        },
      })

      useCanvasStore.getState().clearCanvasActivity('session-1')

      expect(useCanvasStore.getState().unreadCanvasDocIds['session-1']).toEqual([])
    })

    it('removeCanvasDocument removes the document ID from unreadCanvasDocIds', () => {
      useCanvasStore.setState({
        canvases: {
          'session-1': [
            {
              canvasType: 'DOCUMENT',
              chatId: 'session-1',
              content: '# 1',
              documentId: 'doc-1',
              isNewRepo: false,
              title: 'Doc 1',
              version: 1,
            },
            {
              canvasType: 'DOCUMENT',
              chatId: 'session-1',
              content: '# 2',
              documentId: 'doc-2',
              isNewRepo: false,
              title: 'Doc 2',
              version: 1,
            },
          ],
        },
        unreadCanvasDocIds: {
          'session-1': ['doc-1', 'doc-2'],
        },
      })

      useCanvasStore.getState().removeCanvasDocument('session-1', 'doc-1')

      expect(useCanvasStore.getState().unreadCanvasDocIds['session-1']).toEqual(['doc-2'])
    })

    it('clearChatCanvases removes unreadCanvasDocIds for the chat', () => {
      useCanvasStore.setState({
        canvases: {
          'session-1': [
            {
              canvasType: 'DOCUMENT',
              chatId: 'session-1',
              content: '# 1',
              documentId: 'doc-1',
              isNewRepo: false,
              title: 'Doc 1',
              version: 1,
            },
          ],
        },
        unreadCanvasDocIds: {
          'session-1': ['doc-1'],
        },
      })

      useCanvasStore.getState().clearChatCanvases('session-1')

      expect(useCanvasStore.getState().unreadCanvasDocIds['session-1']).toBeUndefined()
    })

    it('clearCanvases resets unreadCanvasDocIds to empty object', () => {
      useCanvasStore.setState({
        unreadCanvasDocIds: {
          'session-1': ['doc-1'],
        },
      })

      useCanvasStore.getState().clearCanvases()

      expect(useCanvasStore.getState().unreadCanvasDocIds).toEqual({})
    })

    it('delete event removes document ID from unreadCanvasDocIds', () => {
      const create1 = createCanvasResult({
        canvasType: 'DOCUMENT',
        chatId: 'session-1',
        content: '# Doc 1',
        documentId: 'doc-1',
        isNewRepo: false,
        title: 'Document 1',
      })
      const create2 = createCanvasResult({
        canvasType: 'DOCUMENT',
        chatId: 'session-1',
        content: '# Doc 2',
        documentId: 'doc-2',
        isNewRepo: false,
        title: 'Document 2',
      })
      useCanvasStore.getState().handleCanvasEvent(create1)
      useCanvasStore.getState().handleCanvasEvent(create2)

      expect(useCanvasStore.getState().unreadCanvasDocIds['session-1']).toEqual(['doc-1', 'doc-2'])

      const deleteResult = createCanvasResult({
        chatId: 'session-1',
        documentId: 'doc-1',
      })
      useCanvasStore.getState().handleCanvasEvent(deleteResult)

      expect(useCanvasStore.getState().unreadCanvasDocIds['session-1']).toEqual(['doc-2'])
    })
  })
})

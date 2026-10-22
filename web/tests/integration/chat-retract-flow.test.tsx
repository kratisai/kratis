import { act } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { useAuthStore } from '@/store/auth-store'
import { useChatStore } from '@/store/chat-store'
import { useWebSocketStore } from '@/store/websocket-store'

import { mockListChats, mockListTeams, setupFetchMock } from '../support/test-fetch-mocks'
import { renderIntegration, screen, setAuthenticated, setUnauthenticated, waitFor } from '../support/test-render'
import {
  setupConnected,
  triggerMockComplete,
  triggerMockMessageChunk,
  triggerMockMessageRetract,
  triggerMockTelemetry,
} from '../support/test-websocket'

describe('Chat retry retraction flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    useChatStore.getState().clearChats()
  })

  afterEach(() => {
    useWebSocketStore.getState().disconnect()
    useAuthStore.getState().logout()
  })

  it('removes a failed attempt before rendering the retry', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])
    mockListChats([])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/chats/session-retry'])
    const ws = setupConnected()

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    act(() => {
      triggerMockTelemetry(ws, 'session-retry', 'Previous turn thought', 1)
      triggerMockComplete(ws, 'session-retry', 'msg-previous', 1)
      triggerMockTelemetry(ws, 'session-retry', 'Stale thought', 1)
      triggerMockMessageChunk(ws, 'session-retry', 'msg-retry', 'stale ', 1)
      triggerMockMessageChunk(ws, 'session-retry', 'msg-retry', 'answer', 1)
    })

    await waitFor(() => {
      expect(screen.getByText('stale answer')).toBeInTheDocument()
    })
    expect(screen.getByText('Stale thought')).toBeInTheDocument()

    act(() => {
      triggerMockMessageRetract(ws, 'session-retry', 'msg-retry', 1)
    })

    await waitFor(() => {
      expect(screen.queryByText('stale answer')).not.toBeInTheDocument()
      expect(screen.queryByText('Stale thought')).not.toBeInTheDocument()
    })
    expect(screen.getByText('Previous turn thought')).toBeInTheDocument()

    act(() => {
      triggerMockTelemetry(ws, 'session-retry', 'Fresh thought', 2)
      triggerMockMessageChunk(ws, 'session-retry', 'msg-retry', 'fresh answer', 2)
    })

    await waitFor(() => {
      expect(screen.getByText('fresh answer')).toBeInTheDocument()
    })
    expect(screen.getByText('Fresh thought')).toBeInTheDocument()
    expect(screen.queryByText(/stale/i)).not.toBeInTheDocument()
  })
})

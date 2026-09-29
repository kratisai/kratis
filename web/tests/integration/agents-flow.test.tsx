import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/store/auth-store'
import { useChatStore } from '@/store/chat-store'
import { useUIStore } from '@/store/ui-store'
import { useWebSocketStore } from '@/store/websocket-store'

import { mockListTeams, setupFetchMock } from '../support/test-fetch-mocks'
import {
  renderIntegration,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'
import { setupConnected } from '../support/test-websocket'

describe('Usage and Ask Kratis Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    vi.clearAllMocks()
    useChatStore.getState().clearChats()
    useUIStore.setState({
      selectedModelName: 'gpt-4',
      selectedProviderId: 'provider-1',
    })
  })

  afterEach(() => {
    useWebSocketStore.getState().disconnect()
    useAuthStore.getState().logout()
    vi.unstubAllGlobals()
  })

  describe('Usage View', () => {
    it('renders the usage view header and dashboard', async () => {
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

      setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

      renderIntegration(['/usage'])

      // Verify header contents
      await waitFor(() => {
        expect(screen.getByRole('heading', { level: 1, name: 'Usage & Analytics' })).toBeInTheDocument()
      })
      expect(
        screen.getByText('Monitor API spend, token utilization, and activity logs across your team.'),
      ).toBeInTheDocument()
    })
  })

  describe('Ask Kratis View', () => {
    it('shows connecting screen when WebSocket is not connected or connecting', async () => {
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

      setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
      renderIntegration(['/ask'])

      await waitFor(() => {
        expect(screen.getByText('Connecting to Kratis...')).toBeInTheDocument()
      })
    })

    it('shows welcome screen and processes suggestions when connected', async () => {
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

      setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
      const { user } = renderIntegration(['/ask'])

      // Connect the WebSocket
      setupConnected()

      // Wait for welcome screen
      await waitFor(() => {
        expect(screen.getByText('What can I help you build today?')).toBeInTheDocument()
      })

      // Check for workflow templates
      expect(screen.getByText('Free-form')).toBeInTheDocument()
      expect(screen.getByText('Plan & Grill')).toBeInTheDocument()

      // Type a task in free-form mode and submit
      const input = screen.getByPlaceholderText('Describe your task...')
      await user.type(input, 'Help me refactor this React component')
      const sendButton = screen.getByRole('button', { name: /send/i })
      await user.click(sendButton)

      // Verify navigation to session view happens after POST /api/v1/chats
      await waitFor(() => {
        expect(screen.getByText('Chat')).toBeInTheDocument()
      })
    })

    it('shows error state when WebSocket connection fails', async () => {
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

      setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
      renderIntegration(['/ask'])

      // Put the websocket store in error state
      useWebSocketStore.setState({
        error: 'Failed to connect to WebSocket',
        isConnected: false,
        isConnecting: false,
      })

      await waitFor(() => {
        expect(screen.getByText('Failed to connect to WebSocket')).toBeInTheDocument()
      })
    })
  })
})

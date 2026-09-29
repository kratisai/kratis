import { beforeEach, describe, expect, it } from 'vitest'

import { useActivityStore } from '@/store/activity-store'
import { useExecutionStore } from '@/store/execution-store'

import {
  mockListChatExecutions,
  mockListChats,
  mockListTeams,
  mockResolvePermission,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderIntegration,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'
import {
  setupConnected,
  triggerMockPermissionRequired,
  triggerMockPermissionResolved,
} from '../support/test-websocket'

const EXECUTION_ID = 'exec-1'
const CHAT_ID = 'session-1'
const TEAM_ID = 'team-1'

function setupSessionView() {
  mockListTeams([
    {
      createdAt: '2024-01-01T00:00:00Z',
      id: TEAM_ID,
      isDefault: true,
      name: 'Test Team',
      role: 'owner',
      tavilyApiKeyConfigured: false,
    },
  ])
  mockListChats([
    {
      createdAt: '2024-01-01T00:00:00Z',
      createdByDisplayName: 'A Test User',
      id: CHAT_ID,
      teamId: TEAM_ID,
      title: 'Test Session',
      updatedAt: '2024-01-01T00:00:00Z',
    },
  ])
  mockListChatExecutions([
    {
      chatId: CHAT_ID,
      completedAt: null,
      exitCode: null,
      id: EXECUTION_ID,
      startedAt: '2024-01-01T00:00:00Z',
      status: 'RUNNING',
    },
  ])
  setAuthenticated({ teamId: TEAM_ID })
  renderIntegration([`/chats/${CHAT_ID}/executions/${EXECUTION_ID}`])
}

describe('Activity Log Multi-Client Sync Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    // Reset execution store
    useExecutionStore.setState({ logs: {} })
    // Reset activity store
    useActivityStore.getState().clearActivities(EXECUTION_ID)
  })

  it('displays permission prompt and resolves when another client approves', async () => {
    mockResolvePermission()
    setupSessionView()

    // Wait for session view to render
    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    // Connect WebSocket
    const ws = setupConnected()

    // Trigger execution_permission_required via WebSocket
    triggerMockPermissionRequired(ws, EXECUTION_ID, 'rm -rf /tmp/build')

    // Assert: Permission prompt appears with command and buttons
    await waitFor(() => {
      expect(screen.getByText('Permission Required')).toBeInTheDocument()
    })
    expect(screen.getByText(/rm -rf \/tmp\/build/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Allow once/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Reject/i })).toBeInTheDocument()

    // Click the allow option
    screen.getByRole('button', { name: /Allow once/i }).click()

    // Trigger execution_permission_resolved via WebSocket (simulating broadcast)
    triggerMockPermissionResolved(
      ws,
      EXECUTION_ID,
      'rm -rf /tmp/build',
      true,
      'user-2',
      'John Doe',
    )

    // Assert: Permission prompt shows resolved state
    await waitFor(() => {
      expect(screen.getByText(/Permission approved/)).toBeInTheDocument()
    })
    // The approve/reject buttons should no longer be visible
    expect(screen.queryByRole('button', { name: /Allow once/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Reject/i })).not.toBeInTheDocument()
  })

  it('shows resolved state when permission was approved by another user', async () => {
    setupSessionView()

    // Wait for session view to render
    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    // Connect WebSocket
    const ws = setupConnected()

    // Trigger execution_permission_required
    triggerMockPermissionRequired(ws, EXECUTION_ID, 'npm install')

    // Assert: Permission prompt appears
    await waitFor(() => {
      expect(screen.getByText('Permission Required')).toBeInTheDocument()
    })
    expect(screen.getByText(/npm install/)).toBeInTheDocument()

    // Trigger execution_permission_resolved (simulating another user approved)
    triggerMockPermissionResolved(ws, EXECUTION_ID, 'npm install', true, 'user-2', 'Jane Smith')

    // Assert: Prompt shows resolved state without action buttons
    await waitFor(() => {
      expect(screen.getByText(/Permission approved/)).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: /Allow once/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Reject/i })).not.toBeInTheDocument()
  })

  it('shows rejected state when permission was rejected by another user', async () => {
    setupSessionView()

    // Wait for session view to render
    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    // Connect WebSocket
    const ws = setupConnected()

    // Trigger execution_permission_required
    triggerMockPermissionRequired(ws, EXECUTION_ID, 'rm -rf /')

    // Assert: Permission prompt appears
    await waitFor(() => {
      expect(screen.getByText('Permission Required')).toBeInTheDocument()
    })

    // Trigger execution_permission_resolved with approved=false
    triggerMockPermissionResolved(ws, EXECUTION_ID, 'rm -rf /', false, 'user-2', 'Admin User')

    // Assert: Prompt shows rejected state
    await waitFor(() => {
      expect(screen.getByText(/Permission rejected/)).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: /Allow once/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Reject/i })).not.toBeInTheDocument()
  })

  it('applies a permission burst in order without losing the resolution', async () => {
    setupSessionView()

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    const ws = setupConnected()

    // Both frames arrive in the same tick, before the batching flush runs.
    triggerMockPermissionRequired(ws, EXECUTION_ID, 'burst command')
    triggerMockPermissionResolved(ws, EXECUTION_ID, 'burst command', true, 'user-2', 'Jane Smith')

    await waitFor(() => {
      expect(screen.getByText(/Permission approved/)).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: /Allow once/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Reject/i })).not.toBeInTheDocument()
  })
})

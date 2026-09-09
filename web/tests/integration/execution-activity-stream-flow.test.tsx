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
  triggerMockExecutionActivity,
  triggerMockPermissionRequired,
  triggerMockPermissionResolved,
} from '../support/test-websocket'

const EXECUTION_ID = 'exec-1'
const SESSION_ID = 'session-1'
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
      createdByDisplayName: 'Test User',
      id: SESSION_ID,
      teamId: TEAM_ID,
      title: 'Test Session',
      updatedAt: '2024-01-01T00:00:00Z',
    },
  ])
  mockListChatExecutions([
    {
      chatId: SESSION_ID,
      completedAt: null,
      exitCode: null,
      id: EXECUTION_ID,
      startedAt: '2024-01-01T00:00:00Z',
      status: 'RUNNING',
    },
  ])
  setAuthenticated({ teamId: TEAM_ID })
  renderIntegration([`/chats/${SESSION_ID}/executions/${EXECUTION_ID}`])
}

describe('Execution Activity Stream with HITL', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    useExecutionStore.setState({ logs: {} })
    useActivityStore.getState().clearActivities(EXECUTION_ID)
  })

  it('streams activities then shows HITL prompt then resolves', async () => {
    mockResolvePermission()
    setupSessionView()

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    const ws = setupConnected()

    // 1. Trigger thinking activity
    triggerMockExecutionActivity(ws, EXECUTION_ID, 'THINKING', 'Analyzing codebase structure')

    // Assert thinking card appears (the thought is both the title and the body)
    await waitFor(() => {
      expect(screen.getAllByText('Analyzing codebase structure').length).toBeGreaterThanOrEqual(1)
    })

    // 2. Trigger tool execution activity
    triggerMockExecutionActivity(ws, EXECUTION_ID, 'RESEARCH', 'file_search')

    // Assert tool card appears
    await waitFor(() => {
      expect(screen.getByText('file_search')).toBeInTheDocument()
    })

    // 3. Trigger permission required (HITL)
    triggerMockPermissionRequired(ws, EXECUTION_ID, 'rm -rf /tmp/build')

    // Assert HITL prompt appears
    await waitFor(() => {
      expect(screen.getByText('Permission Required')).toBeInTheDocument()
    })
    expect(screen.getByText(/rm -rf \/tmp\/build/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Allow once/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Reject/i })).toBeInTheDocument()

    // 4. Click the allow option
    screen.getByRole('button', { name: /Allow once/i }).click()

    // 5. Trigger resolution broadcast
    triggerMockPermissionResolved(ws, EXECUTION_ID, 'rm -rf /tmp/build', true, 'user-2', 'John Doe')

    // Assert resolved state
    await waitFor(() => {
      expect(screen.getByText(/Permission approved/)).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: /Allow once/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Reject/i })).not.toBeInTheDocument()

    // 6. Trigger more activities after resolution
    triggerMockExecutionActivity(ws, EXECUTION_ID, 'THINKING', 'Continuing work')

    await waitFor(() => {
      expect(screen.getAllByText('Continuing work').length).toBeGreaterThanOrEqual(1)
    })
  })

  it('applies a synchronous burst of activity frames in order after batching', async () => {
    setupSessionView()

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    const ws = setupConnected()

    // All frames arrive in the same tick, before the batching flush runs.
    triggerMockExecutionActivity(ws, EXECUTION_ID, 'THINKING', 'Burst thought')
    triggerMockExecutionActivity(
      ws,
      EXECUTION_ID,
      'RESEARCH',
      'burst_search',
      'in_progress',
      'burst-1',
    )
    triggerMockExecutionActivity(
      ws,
      EXECUTION_ID,
      'COMMAND',
      'burst command',
      'in_progress',
      'burst-2',
    )

    await waitFor(() => {
      expect(screen.getByText('Burst thought')).toBeInTheDocument()
    })
    expect(screen.getByText('burst_search')).toBeInTheDocument()
    expect(screen.getByText(/burst command/)).toBeInTheDocument()
  })

  it('displays multiple activity types in timeline', async () => {
    setupSessionView()

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })

    const ws = setupConnected()

    // Trigger multiple activity types
    triggerMockExecutionActivity(ws, EXECUTION_ID, 'THINKING', 'Planning next steps')
    triggerMockExecutionActivity(ws, EXECUTION_ID, 'RESEARCH', 'web_search')
    triggerMockExecutionActivity(ws, EXECUTION_ID, 'COMMAND', 'npm install')

    // Assert all appear in order
    await waitFor(() => {
      expect(screen.getByText('Planning next steps')).toBeInTheDocument()
    })
    await waitFor(() => {
      expect(screen.getByText('web_search')).toBeInTheDocument()
    })
    await waitFor(() => {
      expect(screen.getByText(/npm install/)).toBeInTheDocument()
    })
  })
})

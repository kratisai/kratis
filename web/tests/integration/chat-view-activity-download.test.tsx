import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { CommandExecutionActivity } from '@/types/execution-activity-types'

import { useActivityStore } from '@/store/activity-store'
import { useAuthStore } from '@/store/auth-store'
import { useChatStore } from '@/store/chat-store'
import { useExecutionStore } from '@/store/execution-store'
import { useWebSocketStore } from '@/store/websocket-store'

import { mockDownloads, restoreDownloads } from '../support/test-download'
import { mockListChatExecutions, mockListChats, mockListTeams, setupFetchMock } from '../support/test-fetch-mocks'
import {
  renderIntegration,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'

const EXECUTION_ID = 'exec-1'
const CHAT_ID = 'session-1'
const TEAM_ID = 'team-1'

const commandActivity: CommandExecutionActivity = {
  approvalRequired: false,
  collapsed: false,
  command: 'npm run build',
  endedAt: '2024-06-15T10:31:00.000Z',
  executionId: EXECUTION_ID,
  exitCode: 0,
  id: 'cmd-1',
  output: ['Building project...', 'Build complete'],
  startedAt: '2024-06-15T10:30:00.000Z',
  state: 'completed',
  type: 'command_execution',
}

function seedActivityLog() {
  useActivityStore.setState({
    activitiesByExecution: { [EXECUTION_ID]: [commandActivity] },
  })
}

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
      completedAt: '2024-06-15T10:31:00.000Z',
      exitCode: 0,
      id: EXECUTION_ID,
      startedAt: '2024-06-15T10:30:00.000Z',
      status: 'COMPLETED',
    },
  ])
  setAuthenticated({ teamId: TEAM_ID })
  seedActivityLog()
  return renderIntegration([`/chats/${CHAT_ID}/executions/${EXECUTION_ID}`])
}

describe('Chat View - Activity Log Download', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    useExecutionStore.setState({ logs: {} })
    useChatStore.getState().clearChats()
    useActivityStore.getState().clearActivities(EXECUTION_ID)
    vi.clearAllMocks()
  })

  afterEach(() => {
    useWebSocketStore.getState().disconnect()
    useAuthStore.getState().logout()
    restoreDownloads()
  })

  it('downloads the activity log when the Download button is clicked', async () => {
    const { user } = setupSessionView()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /activity log options/i })).toBeInTheDocument()
    })

    const { clickedAnchors, createObjectURL } = mockDownloads()

    await user.click(screen.getByRole('button', { name: /activity log options/i }))
    await user.click(screen.getByRole('menuitem', { name: /download/i }))

    expect(createObjectURL).toHaveBeenCalledTimes(1)
    const blob = createObjectURL.mock.calls[0][0]
    const blobText = await blob.text()
    expect(blobText).toContain('Execution Activity Log')
    expect(blobText).toContain('[Command Execution] npm run build')
    expect(blobText).toContain('  Building project...')

    expect(clickedAnchors).toHaveLength(1)
    expect(clickedAnchors[0].download).toBe('execution-activity-log-exec-1.txt')

    await waitFor(() => {
      expect(screen.getByText('Activity log downloaded')).toBeInTheDocument()
    })
  })

  it('shows an error toast when the download fails', async () => {
    const { user } = setupSessionView()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /activity log options/i })).toBeInTheDocument()
    })

    const { createObjectURL } = mockDownloads()
    createObjectURL.mockImplementation(() => {
      throw new Error('createObjectURL failed')
    })

    await user.click(screen.getByRole('button', { name: /activity log options/i }))
    await user.click(screen.getByRole('menuitem', { name: /download/i }))

    await waitFor(() => {
      expect(screen.getByText('Failed to download activity log')).toBeInTheDocument()
    })
  })
})

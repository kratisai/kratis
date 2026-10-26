import { act } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { useChatStore } from '@/store/chat-store'
import { useUIStore } from '@/store/ui-store'
import { useWebSocketStore } from '@/store/websocket-store'

import { createMockTeam } from '../support/test-factories'
import { mockListChats, mockListTeams, setupFetchMock } from '../support/test-fetch-mocks'
import {
  renderIntegration,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'
import {
  getLatestInstance,
  type MockWebSocket,
  setupConnected,
  triggerMockComplete,
  triggerMockMessageEcho,
  triggerMockTelemetry,
  triggerMockToolComplete,
} from '../support/test-websocket'

function reconnect(): MockWebSocket {
  useWebSocketStore.getState().connect()
  const ws = getLatestInstance()
  if (!ws) {
    throw new Error('WebSocket instance not created')
  }
  ws.onopen?.()
  ws.onmessage?.({
    data: JSON.stringify({
      id: 0,
      jsonrpc: '2.0',
      result: { status: 'authenticated', type: 'auth', userId: 'user-1' },
    }),
  })
  return ws
}

async function sendChatMessage(
  user: ReturnType<typeof renderIntegration>['user'],
  chatId: string,
  message: string,
) {
  act(() => {
    useUIStore.setState({ selectedModelName: 'model-1', selectedProviderId: 'provider-1' })
  })

  await user.type(screen.getByPlaceholderText(/Type a message/i), message)
  await user.keyboard('{Enter}')

  await waitFor(() => {
    expect(screen.getByText(message)).toBeInTheDocument()
  })
  expect(useChatStore.getState().sendingChatIds.has(chatId)).toBe(true)
}

describe('Chat reconnect flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    useChatStore.getState().clearChats()
  })

  afterEach(() => {
    useWebSocketStore.getState().disconnect()
  })

  it('clears the working indicator when the turn completed while disconnected', async () => {
    const team = createMockTeam()
    const chatId = 'chat-reconnect-complete'
    mockListTeams([team])
    mockListChats([
      {
        createdAt: '2024-01-01T00:00:00Z',
        createdByDisplayName: 'Test User',
        id: chatId,
        teamId: team.id,
        title: 'Reconnect complete',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ])

    setAuthenticated({ teamId: team.id, userId: 'user-1' })
    const { user } = renderIntegration([`/chats/${chatId}`])
    const ws = setupConnected()

    await waitFor(() => {
      expect(screen.getByPlaceholderText(/Type a message/i)).not.toBeDisabled()
    })

    await sendChatMessage(user, chatId, 'Plan the migration')

    act(() => {
      triggerMockTelemetry(ws, chatId, 'Checking the repository', 1, 'task-1', 'read_file')
    })

    await waitFor(() => {
      expect(screen.getByTestId('agent-working-block')).toBeInTheDocument()
    })

    act(() => {
      ws.onclose?.()
    })

    await waitFor(() => {
      expect(screen.getByText('Disconnected')).toBeInTheDocument()
    })

    const reconnected = reconnect()

    // The backend replays the persisted history, then the history-replay marker.
    act(() => {
      triggerMockMessageEcho(reconnected, 'Plan the migration', chatId, 'user', 'msg-user-1')
      triggerMockMessageEcho(
        reconnected,
        'The migration plan is ready.',
        chatId,
        'assistant',
        'msg-assistant-1',
      )
      triggerMockComplete(reconnected, chatId, undefined, 2)
    })

    await waitFor(() => {
      expect(screen.getByText('The migration plan is ready.')).toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Agent working' })).not.toBeInTheDocument()
    })

    const input = screen.getByPlaceholderText(/Type a message/i)
    expect(input).not.toBeDisabled()

    await user.type(input, 'Next step')
    expect(screen.getByRole('button', { name: 'Send' })).not.toBeDisabled()

    expect(screen.getByTestId('plan-shortcut')).toBeInTheDocument()
  })

  it('does not duplicate tools when telemetry is replayed after reconnecting to a running turn', async () => {
    const team = createMockTeam()
    const chatId = 'chat-reconnect-running'
    mockListTeams([team])
    mockListChats([
      {
        createdAt: '2024-01-01T00:00:00Z',
        createdByDisplayName: 'Test User',
        id: chatId,
        teamId: team.id,
        title: 'Reconnect running',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ])

    setAuthenticated({ teamId: team.id, userId: 'user-1' })
    const { user } = renderIntegration([`/chats/${chatId}`])
    const ws = setupConnected()

    await waitFor(() => {
      expect(screen.getByPlaceholderText(/Type a message/i)).not.toBeDisabled()
    })

    await sendChatMessage(user, chatId, 'Fix the failing build')

    act(() => {
      triggerMockTelemetry(ws, chatId, 'Analyzing the failure', 1)
      triggerMockTelemetry(ws, chatId, 'Run the test suite', 1, 'task-1', 'run_tests')
      triggerMockToolComplete(ws, chatId, 'task-1')
    })

    await waitFor(() => {
      expect(screen.getByText('Run the test suite')).toBeInTheDocument()
    })

    act(() => {
      ws.onclose?.()
    })

    await waitFor(() => {
      expect(screen.getByText('Disconnected')).toBeInTheDocument()
    })

    const reconnected = reconnect()

    // History replay only contains the user message: the turn is still running.
    act(() => {
      triggerMockMessageEcho(reconnected, 'Fix the failing build', chatId, 'user', 'msg-user-2')
      triggerMockComplete(reconnected, chatId, undefined, 1)
    })

    // ChatFluxRegistry replays the whole turn, starting from iteration 1.
    act(() => {
      triggerMockTelemetry(reconnected, chatId, 'Analyzing the failure', 1)
      triggerMockTelemetry(reconnected, chatId, 'Run the test suite', 1, 'task-1', 'run_tests')
      triggerMockToolComplete(reconnected, chatId, 'task-1')
      triggerMockTelemetry(reconnected, chatId, 'Write the summary', 1, 'task-2', 'write_file')
      triggerMockToolComplete(reconnected, chatId, 'task-2')
      triggerMockMessageEcho(
        reconnected,
        'All checks passed.',
        chatId,
        'assistant',
        'msg-assistant-2',
      )
      triggerMockComplete(reconnected, chatId, 'msg-assistant-2', 2)
    })

    await waitFor(() => {
      expect(screen.getByText('All checks passed.')).toBeInTheDocument()
    })

    expect(screen.getAllByText('Analyzing the failure')).toHaveLength(1)
    expect(screen.getAllByText('Run the test suite')).toHaveLength(1)
    expect(screen.getAllByText('Write the summary')).toHaveLength(1)

    const input = screen.getByPlaceholderText(/Type a message/i)
    expect(input).not.toBeDisabled()

    await user.type(input, 'Next step')
    expect(screen.getByRole('button', { name: 'Send' })).not.toBeDisabled()
  })
})

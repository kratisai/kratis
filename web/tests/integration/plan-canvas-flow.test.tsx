import { beforeEach, describe, expect, it } from 'vitest'

import { useCanvasStore } from '@/store/canvas-store'
import { useChatStore } from '@/store/chat-store'
import { useUIStore } from '@/store/ui-store'

import { createMockTeam } from '../support/test-factories'
import {
  addFetchHandler,
  mockListChats,
  mockListEnvironments,
  mockListHarnesses,
  mockListModelProviders,
  mockListProviders,
  mockListTeams,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderIntegration,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'

describe('Plan Canvas to Execution Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    useChatStore.getState().clearChats()
    useCanvasStore.getState().clearCanvases()
    useUIStore.setState({ selectedModelName: null, selectedProviderId: null })
  })

  it('passes canvasId and harness when launching execution from canvas panel', async () => {
    const team = createMockTeam()

    // Register execution handler FIRST (before mockListChats which has a broad URL pattern)
    const executionPayloads: Array<Record<string, unknown>> = []
    addFetchHandler((url, options) => {
      if (url.includes('/executions') && options.method === 'POST') {
        const body = JSON.parse((options.body as string) || '{}')
        executionPayloads.push(body)
        return new Response(
          JSON.stringify({
            chatId: 'session-1',
            createdAt: '2024-01-01T00:00:00Z',
            environmentId: 'env-1',
            id: 'exec-1',
            startedAt: '2024-01-01T00:00:00Z',
            status: 'PENDING',
          }),
          { headers: { 'Content-Type': 'application/json' }, status: 201 },
        )
      }
      return null
    })

    mockListTeams([team])
    mockListChats([
      {
        createdAt: '2024-01-01T00:00:00Z',
        createdByDisplayName: 'Test User',
        id: 'session-1',
        teamId: team.id,
        title: 'Test Session',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ])
    mockListProviders([{ id: 'provider-1', name: 'Docker', teamId: team.id }])
    mockListEnvironments([])
    mockListHarnesses()
    mockListModelProviders([
      {
        id: 'model-provider-1',
        models: [{ kind: 'CHAT', modelName: 'gpt-4' }],
        name: 'Test Model Provider',
      },
    ])

    setAuthenticated({ teamId: team.id, userId: 'user-1' })

    // Pre-populate canvas store with a plan document
    useCanvasStore.setState({
      canvases: {
        'session-1': [
          {
            canvasType: 'SPEC',
            chatId: 'session-1',
            content: '# Plan\n1. Step one\n2. Step two',
            documentId: 'plan-doc-1',
            isNewRepo: false,
            title: 'Execution Plan',
            version: 1,
          },
        ],
      },
    })

    const { user } = renderIntegration(['/chats/session-1'])

    // Wait for canvas to render (title appears in the canvas tab and the
    // stage-nav sub-stage switcher)
    await waitFor(() => {
      expect(screen.getAllByText('Execution Plan').length).toBeGreaterThan(0)
    })

    // Click Run button
    const runButton = screen.getByRole('button', { name: /run/i })
    await user.click(runButton)

    // Dialog should open
    await waitFor(() => {
      expect(screen.getByText('Launch Execution')).toBeInTheDocument()
    })

    // Select execution target - click the first combobox trigger
    const targetTriggers = screen.getAllByRole('combobox')
    await user.click(targetTriggers[0])

    await waitFor(() => {
      expect(screen.getByText('Docker (Docker)')).toBeInTheDocument()
    })
    await user.click(screen.getByText('Docker (Docker)'))

    // Select harness - re-query triggers since DOM may have updated
    await waitFor(() => {
      const harnessTriggers = screen.getAllByRole('combobox')
      expect(harnessTriggers.length).toBeGreaterThanOrEqual(2)
    })
    const harnessTriggers = screen.getAllByRole('combobox')
    await user.click(harnessTriggers[1])

    await waitFor(() => {
      expect(screen.getByText('OpenCode')).toBeInTheDocument()
    })
    await user.click(screen.getByText('OpenCode'))

    // Click Launch
    const launchButton = screen.getByRole('button', { name: /launch/i })
    await user.click(launchButton)

    // Assert createSandboxExecution was called with canvasId and harness
    await waitFor(() => {
      expect(executionPayloads.length).toBe(1)
      expect(executionPayloads[0]).toMatchObject({
        canvasId: 'plan-doc-1',
        harness: 'OPENCODE',
        providerId: 'provider-1',
      })
    })
  })
})

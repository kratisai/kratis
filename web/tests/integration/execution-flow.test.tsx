import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { CreateSandboxExecutionRequest } from '@/lib/execution-api'

import { useAuthStore } from '@/store/auth-store'
import { useCanvasStore } from '@/store/canvas-store'
import { useChatStore } from '@/store/chat-store'
import { useExecutionStore } from '@/store/execution-store'
import { useUIStore } from '@/store/ui-store'

import {
  addFetchHandler,
  mockListHarnesses,
  mockListModelProviders,
  mockListTeams,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderIntegration,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
  waitForErrorToast,
} from '../support/test-render'

describe('Execution and Sandbox Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    vi.clearAllMocks()
    useChatStore.getState().clearChats()
    useCanvasStore.setState({ canvases: {} })
    useExecutionStore.setState({
      logs: {},
      terminalOpen: false,
    })
    useUIStore.setState({ selectedModelName: null, selectedProviderId: null })
  })

  afterEach(() => {
    useAuthStore.getState().logout()
  })

  it('handles sandbox execution provisioning and terminal lifecycle', async () => {
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

    // Mock Get Providers
    addFetchHandler((url, options) => {
      if (url.includes('/api/v1/teams/team-1/environment-providers') && (!options.method || options.method === 'GET')) {
        return new Response(
          JSON.stringify([
            { id: 'provider-1', name: 'Docker Local Provider', teamId: 'team-1' },
          ]),
          { headers: { 'Content-Type': 'application/json' }, status: 200 }
        )
      }
      return null
    })

    // Mock Get Environments
    addFetchHandler((url, options) => {
      if (url.includes('/api/v1/teams/team-1/environments') && (!options.method || options.method === 'GET')) {
        return new Response(
          JSON.stringify([
            {
              createdAt: '2024-01-01T00:00:00Z',
              id: 'env-connector-1',
              name: 'My Workspace Connector',
              status: 'CONNECTED',
              teamId: 'team-1',
              type: 'CONNECTOR',
              updatedAt: '2024-01-01T00:00:00Z',
            },
          ]),
          { headers: { 'Content-Type': 'application/json' }, status: 200 }
        )
      }
      return null
    })

    // Mock Get Harnesses
    mockListHarnesses()

    // Mock Get Model Providers (auto-selected as the chat model to run executions with)
    mockListModelProviders([
      {
        id: 'model-provider-1',
        models: [{ kind: 'CHAT', modelName: 'gpt-4' }],
        name: 'Test Model Provider',
      },
    ])

    // Track execution API requests
    let executionPayload: CreateSandboxExecutionRequest | null = null
    addFetchHandler((url, options) => {
      if (url.endsWith('/api/v1/chats/session-1/executions') && options.method === 'POST') {
        executionPayload = JSON.parse(options.body as string)
        return new Response(
          JSON.stringify({
            chatId: 'session-1',
            createdAt: '2024-01-01T00:00:00Z',
            id: 'execution-1',
            status: 'PENDING',
            updatedAt: '2024-01-01T00:00:00Z',
          }),
          { headers: { 'Content-Type': 'application/json' }, status: 201 }
        )
      }
      return null
    })

    // Execution list: gated so the initial route resolution defaults to the canvas view; once
    // the launch is in flight, the execution view and terminate menu derive state from it.
    let executionListAvailable = false
    addFetchHandler((url, options) => {
      if (url.endsWith('/api/v1/chats/session-1/executions') && (!options.method || options.method === 'GET')) {
        if (!executionListAvailable) return null
        return new Response(
          JSON.stringify([
            {
              chatId: 'session-1',
              completedAt: null,
              exitCode: null,
              id: 'execution-1',
              startedAt: '2024-01-01T00:00:00Z',
              status: 'RUNNING',
            },
          ]),
          { headers: { 'Content-Type': 'application/json' }, status: 200 },
        )
      }
      if (url.includes('/diff/summary')) {
        return new Response(
          JSON.stringify({
            baseBranch: 'main',
            files: [],
            totalAdditions: 0,
            totalDeletions: 0,
          }),
          { headers: { 'Content-Type': 'application/json' }, status: 200 },
        )
      }
      return null
    })

    // Track terminate API requests (execution-scoped endpoint)
    let terminatedExecutionId: null | string = null
    addFetchHandler((url, options) => {
      if (url.includes('/api/v1/chats/session-1/executions/execution-1/terminate') && options.method === 'POST') {
        terminatedExecutionId = 'execution-1'
        return new Response(null, { status: 202 })
      }
      return null
    })

    // Setup Zustand stores with a session and a canvas doc
    useCanvasStore.setState({
      canvases: {
        'session-1': [
          {
            canvasType: 'SPEC',
            chatId: 'session-1',
            content: 'console.log("hello world")',
            documentId: 'doc-1',
            isNewRepo: false,
            title: 'main.js',
            version: 1,
          },
        ],
      },
    })

    useChatStore.setState({
      currentChatId: 'session-1',
      messages: {
        'session-1': [
          {
            content: 'Let\'s run this JS file.',
            id: 'msg-1',
            role: 'user',
            timestamp: new Date(),
          },
        ],
      },
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    const { user } = renderIntegration(['/chats/session-1'])

    // Wait for the Session view to render (title appears in the canvas tab and
    // the stage-nav sub-stage switcher)
    await waitFor(() => {
      expect(screen.getAllByText('main.js').length).toBeGreaterThan(0)
    })

    // Trigger Run button to open launch dialog
    const runButton = screen.getByRole('button', { name: /run/i })
    await user.click(runButton)

    // Dialog should open - select target
    await waitFor(() => {
      expect(screen.getByText('Launch Execution')).toBeInTheDocument()
    })
    const triggers = screen.getAllByRole('combobox')
    await user.click(triggers[0])
    await user.click(screen.getByText('Docker (Docker Local Provider)'))

    // Select harness
    await user.click(triggers[1])
    await user.click(screen.getByText('OpenCode'))

    // Click Launch
    executionListAvailable = true
    await user.click(screen.getByRole('button', { name: /launch/i }))

    // Check that execution API was triggered with canvasId and harness
    await waitFor(() => {
      expect(executionPayload).toEqual({
        canvasId: 'doc-1',
        harness: 'OPENCODE',
        modelName: 'gpt-4',
        modelProviderId: 'model-provider-1',
        providerId: 'provider-1',
      })
    })

    // The terminal opens with only server-sent output; no client-side narration is injected
    await waitFor(() => {
      expect(screen.getByTestId('terminal-panel')).toBeInTheDocument()
    })
    expect(screen.queryByText(/Launching sandbox execution/i)).not.toBeInTheDocument()
    const optionsButton = screen.getByRole('button', { name: /activity log options/i })
    await user.click(optionsButton)
    await user.click(screen.getByRole('menuitem', { name: /terminate/i }))

    // Check that the execution terminate endpoint was called
    await waitFor(() => {
      expect(terminatedExecutionId).toBe('execution-1')
    })
  })

  it('handles sandbox execution error states', async () => {
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

    // Mock Get Providers
    addFetchHandler((url, options) => {
      if (url.includes('/api/v1/teams/team-1/environment-providers') && (!options.method || options.method === 'GET')) {
        return new Response(
          JSON.stringify([
            { id: 'provider-1', name: 'Docker Local Provider', teamId: 'team-1' },
          ]),
          { headers: { 'Content-Type': 'application/json' }, status: 200 }
        )
      }
      return null
    })

    // Mock Get Environments (empty list)
    addFetchHandler((url, options) => {
      if (url.includes('/api/v1/teams/team-1/environments') && (!options.method || options.method === 'GET')) {
        return new Response(JSON.stringify([]), { status: 200 })
      }
      return null
    })

    // Mock Get Harnesses
    mockListHarnesses()

    // Mock Get Model Providers (auto-selected as the chat model to run executions with)
    mockListModelProviders([
      {
        id: 'model-provider-1',
        models: [{ kind: 'CHAT', modelName: 'gpt-4' }],
        name: 'Test Model Provider',
      },
    ])

    // Mock Failed Post Execution
    addFetchHandler((url, options) => {
      if (url.includes('/api/v1/chats/session-1/executions') && options.method === 'POST') {
        return new Response(
          JSON.stringify({ message: 'Insufficient resources' }),
          { headers: { 'Content-Type': 'application/json' }, status: 400 }
        )
      }
      return null
    })

    // Setup Zustand stores with a session and a canvas doc
    useCanvasStore.setState({
      canvases: {
        'session-1': [
          {
            canvasType: 'SPEC',
            chatId: 'session-1',
            content: 'console.log("hello world")',
            documentId: 'doc-1',
            isNewRepo: false,
            title: 'main.js',
            version: 1,
          },
        ],
      },
    })

    useChatStore.setState({
      currentChatId: 'session-1',
      messages: {
        'session-1': [
          {
            content: 'Let\'s run this JS file.',
            id: 'msg-1',
            role: 'user',
            timestamp: new Date(),
          },
        ],
      },
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    const { user } = renderIntegration(['/chats/session-1'])

    // Wait for the Session view to render (title appears in the canvas tab and
    // the stage-nav sub-stage switcher)
    await waitFor(() => {
      expect(screen.getAllByText('main.js').length).toBeGreaterThan(0)
    })

    // Trigger Run button to open launch dialog
    const runButton = screen.getByRole('button', { name: /run/i })
    await user.click(runButton)

    // Dialog should open - select target
    await waitFor(() => {
      expect(screen.getByText('Launch Execution')).toBeInTheDocument()
    })
    const triggers = screen.getAllByRole('combobox')
    await user.click(triggers[0])
    await user.click(screen.getByText('Docker (Docker Local Provider)'))

    // Select harness
    await user.click(triggers[1])
    await user.click(screen.getByText('OpenCode'))

    // Click Launch
    await user.click(screen.getByRole('button', { name: /launch/i }))

    // Verify an error toast shows the execution failure details
    await waitForErrorToast('Failed to launch execution: Insufficient resources')
  })
})

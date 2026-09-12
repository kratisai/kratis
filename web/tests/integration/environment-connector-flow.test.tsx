import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  createMockTeam,
} from '../support/test-factories'
import {
  addFetchHandler,
  jsonResponse,
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

describe('Environment Connector Flow (Flow C)', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    vi.stubEnv('VITE_ENABLE_ENVIRONMENT_FEATURES', 'true')
  })

  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('completes the full environment connector lifecycle: create connector, view install command, and simulate WebSocket heartbeat', async () => {
    // 1. User navigates to `/settings` and is an owner of an existing team.
    mockListTeams([createMockTeam()])

    addFetchHandler((url) => {
      if (url === '/api/v1/teams/team-1') {
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: 'Test team',
          id: 'team-1',
          isDefault: true,
          members: [
            {
              displayName: 'Test User',
              email: 'test@test.com',
              id: 'member-1',
              role: 'owner',
              userId: 'user-1',
            },
          ],
          name: 'Test Team',
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      return null
    })

    // 2. User views the "Environment Providers" and "Execution Environments" sections.
    let postPayload: unknown = null
    let isConnected = false
    addFetchHandler((url, options) => {
      if (url.includes('/model-providers') && (!options.method || options.method === 'GET')) {
        return jsonResponse([])
      }
      if (url.includes('/environment-providers') && (!options.method || options.method === 'GET')) {
        return jsonResponse([
          {
            dockerImage: 'kratis-runner-base:latest',
            id: 'provider-1',
            name: 'Default Docker Provider',
            teamId: 'team-1',
          },
        ])
      }
      if (url.includes('/environments') && (!options.method || options.method === 'GET')) {
        const envs: Array<Record<string, unknown>> = []
        if (postPayload) {
          envs.push({
            containerId: null,
            createdAt: '2024-01-01T00:00:00Z',
            id: 'env-new',
            lastHeartbeat: isConnected ? new Date().toISOString() : null,
            name: (postPayload as { name: string }).name,
            status: isConnected ? 'CONNECTED' : 'DISCONNECTED',
            teamId: 'team-1',
            type: 'CONNECTOR',
            updatedAt: '2024-01-01T00:00:00Z',
          })
        }
        return jsonResponse(envs)
      }
      return null
    })

    addFetchHandler((url, options) => {
      if (url.includes('/environments') && options.method?.toUpperCase() === 'POST') {
        postPayload = JSON.parse(options.body as string)
        return jsonResponse(
          {
            environment: {
              containerId: null,
              createdAt: '2024-01-01T00:00:00Z',
              id: 'env-new',
              lastHeartbeat: null,
              name: (postPayload as { name: string }).name,
              status: 'DISCONNECTED',
              teamId: 'team-1',
              type: 'CONNECTOR',
              updatedAt: '2024-01-01T00:00:00Z',
            },
            installCommand: 'kratis-connector --mode=daemon --server-url=ws://localhost:8080/ws/env --token=test-auth-token-123',
          },
          201
        )
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { queryClient, user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getByText('Environment Providers')).toBeInTheDocument()
      expect(screen.getByText('Execution Environments')).toBeInTheDocument()
    })

    // 4. User clicks "Add Workspace Connector" to open the create connector dialog.
    const addButton = screen.getByRole('button', { name: /add workspace connector/i })
    await user.click(addButton)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Add Workspace Connector' })).toBeInTheDocument()
    })

    // 5. User fills out the new environment form (Name, Type: CONNECTOR) and submits.
    const nameInput = screen.getByPlaceholderText('e.g., My Local Workspace')
    await user.clear(nameInput)
    await user.type(nameInput, 'My New Connector')

    const submitButton = screen.getByRole('button', { name: 'Create Connector' })
    await user.click(submitButton)

    // 6. Verify success toast appears and the "Install Command" modal is shown with the connection string.
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Workspace Connector Created' })).toBeInTheDocument()
      expect(screen.getByText(/kratis-connector --mode=daemon/)).toBeInTheDocument()
    })

    // 7. User closes the modal.
    const closeButtons = screen.getAllByRole('button', { name: 'Close' })
    await user.click(closeButtons[0])

    // 8. Verify the new environment appears in the list with "DISCONNECTED" status.
    await waitFor(() => {
      expect(screen.getAllByText('My New Connector').length).toBeGreaterThan(0)
      expect(screen.getAllByText('CONNECTOR').length).toBeGreaterThan(0)
      expect(screen.getAllByText('Disconnected').length).toBeGreaterThan(0)
    })

    // 9. Simulate a WebSocket heartbeat message for this environment and verify the UI updates the status to "CONNECTED".
    // Update the mock state to return the connected environment on next fetch
    isConnected = true

    // Simulate the WebSocket-triggered refetch by invalidating the query
    void queryClient.invalidateQueries({ queryKey: ['environments', 'team-1'] })

    await waitFor(() => {
      expect(screen.getAllByText('Connected').length).toBeGreaterThan(0)
    })
  })
})



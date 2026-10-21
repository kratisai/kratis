import { within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  addFetchHandler,
  jsonResponse,
  mockCreateEnvironment,
  mockDeleteEnvironment,
  mockHaltExecution,
  mockListEnvironments,
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

describe('Environments Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('renders environments section in settings', async () => {
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
    mockListEnvironments([])
    mockListModelProvidersEmpty()
    mockListCredentialsEmpty()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      const elements = screen.getAllByText(/Environments/i)
      expect(elements.length).toBeGreaterThan(0)
    })
  })

  it('shows existing environments', async () => {
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
    mockListEnvironments([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'env-1',
        lastHeartbeat: null,
        name: 'My Connector',
        status: 'PENDING_CONNECT',
        teamId: 'team-1',
        type: 'CONNECTOR',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ])
    mockListModelProvidersEmpty()
    mockListCredentialsEmpty()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getAllByText('My Connector').length).toBeGreaterThan(0)
    })
  })

  it('creates a new connector environment', async () => {
    vi.stubEnv('VITE_ENABLE_ENVIRONMENT_FEATURES', 'true')

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
    mockListEnvironments([])
    mockCreateEnvironment()
    mockListModelProvidersEmpty()
    mockListCredentialsEmpty()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getByText('Execution Environments')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /add workspace connector/i }))

    await waitFor(() => {
      expect(screen.getByTestId('connector-name-field')).toBeInTheDocument()
    })

    const nameField = screen.getByTestId('connector-name-field')
    expect(nameField).toHaveClass('grid-cols-1')
    expect(nameField).toHaveClass('sm:grid-cols-4')

    await user.type(screen.getByLabelText(/name/i), 'New Connector')

    await user.click(screen.getByRole('button', { name: 'Create Connector' }))

    await waitFor(() => {
      expect(screen.getByText('Connector created successfully')).toBeInTheDocument()
    })
  })

  it('shows environment status badges correctly', async () => {
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
    mockListEnvironments([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'env-connected',
        lastHeartbeat: '2024-01-01T00:00:00Z',
        name: 'Connected Env',
        status: 'CONNECTED',
        teamId: 'team-1',
        type: 'CONNECTOR',
        updatedAt: '2024-01-01T00:00:00Z',
      },
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'env-pending',
        lastHeartbeat: null,
        name: 'Pending Env',
        status: 'PENDING_CONNECT',
        teamId: 'team-1',
        type: 'CONNECTOR',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ])
    mockListModelProvidersEmpty()
    mockListCredentialsEmpty()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getAllByText('Connected Env').length).toBeGreaterThan(0)
    })
    expect(screen.getAllByText('Active').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Pending Env').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Connecting').length).toBeGreaterThan(0)
  })

  it('renders environments as mobile cards alongside the desktop table', async () => {
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
    mockListEnvironments([
      {
        connected: true,
        createdAt: '2024-01-01T00:00:00Z',
        id: 'env-connected',
        lastHeartbeat: '2024-01-01T00:00:00Z',
        name: 'Connected Env',
        status: 'CONNECTED',
        teamId: 'team-1',
        type: 'CONNECTOR',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ])
    mockListModelProvidersEmpty()
    mockListCredentialsEmpty()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getByRole('table')).toBeInTheDocument()
    })
    const cards = screen.getByTestId('environment-cards')
    expect(within(cards).getByText('Connected Env')).toBeInTheDocument()
    expect(within(cards).getByText('CONNECTOR')).toBeInTheDocument()
    expect(within(cards).queryByRole('button', { name: /delete connected env/i })).not.toBeInTheDocument()
  })

  it('deletes an environment with confirmation', async () => {
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
    mockListEnvironments([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'env-1',
        lastHeartbeat: null,
        name: 'Old Sandbox',
        status: 'TERMINATED',
        teamId: 'team-1',
        type: 'SANDBOX',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ])
    mockDeleteEnvironment('env-1')
    mockListModelProvidersEmpty()
    mockListCredentialsEmpty()

    // Mock window.confirm
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getAllByText('Old Sandbox').length).toBeGreaterThan(0)
    })

    // Click delete button
    const deleteButton = within(screen.getByRole('table')).getByRole('button', {
      name: /delete old sandbox/i,
    })
    await user.click(deleteButton)

    expect(confirmSpy).toHaveBeenCalledWith('Are you sure you want to delete "Old Sandbox"?')
    confirmSpy.mockRestore()

    // Success toast
    await waitFor(() => {
      expect(screen.getByText('Environment deleted successfully')).toBeInTheDocument()
    })
  })

  it('halts a running sandbox environment through the execution halt API', async () => {
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
    mockListEnvironments([
      {
        activeChatId: 'chat-1',
        activeExecutionId: 'exec-1',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'env-sandbox',
        lastHeartbeat: '2024-01-01T00:00:00Z',
        name: 'Running Sandbox',
        status: 'CONNECTED',
        teamId: 'team-1',
        type: 'SANDBOX',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ])
    mockHaltExecution('chat-1', 'exec-1')
    mockListModelProvidersEmpty()
    mockListCredentialsEmpty()

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getAllByText('Running Sandbox').length).toBeGreaterThan(0)
    })

    const haltButton = within(screen.getByRole('table')).getByRole('button', {
      name: /halt running sandbox/i,
    })
    await user.click(haltButton)

    expect(confirmSpy).toHaveBeenCalledWith('Are you sure you want to halt "Running Sandbox"?')
    confirmSpy.mockRestore()

    await waitFor(() => {
      expect(screen.getByText('Execution halted')).toBeInTheDocument()
    })
  })

  it('does not show a halt button when an environment has no active execution', async () => {
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
    mockListEnvironments([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'env-1',
        lastHeartbeat: null,
        name: 'My Connector',
        status: 'PENDING_CONNECT',
        teamId: 'team-1',
        type: 'CONNECTOR',
        updatedAt: '2024-01-01T00:00:00Z',
      },
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'env-stopped',
        lastHeartbeat: null,
        name: 'Stopped Sandbox',
        status: 'PENDING_CONNECT',
        teamId: 'team-1',
        type: 'SANDBOX',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ])
    mockListModelProvidersEmpty()
    mockListCredentialsEmpty()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getAllByText('My Connector').length).toBeGreaterThan(0)
    })

    expect(screen.queryByRole('button', { name: /halt/i })).not.toBeInTheDocument()
  })

  it('shows connector created dialog with install command after creation', async () => {
    vi.stubEnv('VITE_ENABLE_ENVIRONMENT_FEATURES', 'true')

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
    mockListEnvironments([])
    mockCreateEnvironment({
      installCommand: 'kratis-connector --mode=daemon --server-url=ws://localhost:8080/ws/env --token=abc',
    })
    mockListModelProvidersEmpty()
    mockListCredentialsEmpty()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getByText('Execution Environments')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /add workspace connector/i }))

    await waitFor(() => {
      expect(screen.getByLabelText(/name/i)).toBeInTheDocument()
    })

    await user.type(screen.getByLabelText(/name/i), 'Test Connector')

    await user.click(screen.getByRole('button', { name: 'Create Connector' }))

    await waitFor(() => {
      expect(screen.getByText('Workspace Connector Created')).toBeInTheDocument()
    })
    expect(screen.getByText(/kratis-connector --mode=daemon/)).toBeInTheDocument()
  })

  it('hides the Add Workspace Connector button when environment features are disabled', async () => {
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
    mockListEnvironments([])
    mockListModelProvidersEmpty()
    mockListCredentialsEmpty()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getByText('Execution Environments')).toBeInTheDocument()
    })
    expect(
      screen.queryByRole('button', { name: /add workspace connector/i }),
    ).not.toBeInTheDocument()
  })
})

function mockListCredentialsEmpty() {
  addFetchHandler((url, options) => {
    const match = url.match(/\/api\/v1\/teams\/[^/]+\/credentials$/)
    if (match && (!options.method || options.method === 'GET')) {
      return jsonResponse([])
    }
    return null
  })
}

// Helpers to mock empty data for other settings sections
function mockListModelProvidersEmpty() {
  addFetchHandler((url) => {
    if (url.includes('/model-providers/teams/')) {
      return jsonResponse([])
    }
    if (url.includes('/model-providers/supported-types')) {
      return jsonResponse([])
    }
    return null
  })
}

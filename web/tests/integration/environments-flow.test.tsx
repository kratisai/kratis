import { within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  addFetchHandler,
  jsonResponse,
  mockCreateEnvironment,
  mockDeleteEnvironment,
  mockListEnvironments,
  mockListTeams,
  mockTerminateEnvironment,
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
        containerId: null,
        createdAt: '2024-01-01T00:00:00Z',
        id: 'env-1',
        lastHeartbeat: null,
        name: 'My Connector',
        status: 'DISCONNECTED',
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
      const elements = screen.getAllByText(/Environments/i)
      expect(elements.length).toBeGreaterThan(0)
    })

    // Look for "Add Connector" or "Add Environment" button
    const addButton =
      screen.queryByRole('button', { name: /add connector/i }) ||
      screen.queryByRole('button', { name: /add environment/i }) ||
      screen.queryByRole('button', { name: /new connector/i })

    if (addButton) {
      await user.click(addButton)

      // Fill in name
      await waitFor(() => {
        const nameInputs = screen.getAllByLabelText(/name/i)
        expect(nameInputs.length).toBeGreaterThan(0)
      })

      const nameField = screen.getByTestId('connector-name-field')
      expect(nameField).toHaveClass('grid-cols-1')
      expect(nameField).toHaveClass('sm:grid-cols-4')

      const nameInput = screen.getAllByLabelText(/name/i)[0]
      await user.type(nameInput, 'New Connector')

      // Submit
      const submitButtons = screen.getAllByRole('button', { name: /create/i })
      await user.click(submitButtons[0])

      // Success toast
      await waitFor(() => {
        expect(screen.getByText('Connector created successfully')).toBeInTheDocument()
      })
    }
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
        containerId: 'abc123',
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
        containerId: null,
        createdAt: '2024-01-01T00:00:00Z',
        id: 'env-pending',
        lastHeartbeat: null,
        name: 'Pending Env',
        status: 'PENDING_RECONNECT',
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
    expect(screen.getAllByText('Connected').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Pending Env').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Pending Reconnect').length).toBeGreaterThan(0)
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
        containerId: 'abc123',
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
    expect(within(cards).getByText('abc123')).toBeInTheDocument()
    expect(within(cards).getByText('CONNECTOR')).toBeInTheDocument()
    expect(within(cards).getByRole('button', { name: /delete connected env/i })).toBeInTheDocument()
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
        containerId: null,
        createdAt: '2024-01-01T00:00:00Z',
        id: 'env-1',
        lastHeartbeat: null,
        name: 'My Connector',
        status: 'DISCONNECTED',
        teamId: 'team-1',
        type: 'CONNECTOR',
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
      expect(screen.getAllByText('My Connector').length).toBeGreaterThan(0)
    })

    // Click delete button
    const deleteButton = within(screen.getByRole('table')).getByRole('button', {
      name: /delete my connector/i,
    })
    await user.click(deleteButton)

    expect(confirmSpy).toHaveBeenCalledWith('Are you sure you want to delete "My Connector"?')
    confirmSpy.mockRestore()

    // Success toast
    await waitFor(() => {
      expect(screen.getByText('Environment deleted successfully')).toBeInTheDocument()
    })
  })

  it('terminates a running sandbox environment with confirmation', async () => {
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
        containerId: 'container-1',
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
    mockTerminateEnvironment('env-sandbox')
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

    const terminateButton = within(screen.getByRole('table')).getByRole('button', {
      name: /terminate running sandbox/i,
    })
    await user.click(terminateButton)

    expect(confirmSpy).toHaveBeenCalledWith('Are you sure you want to terminate "Running Sandbox"?')
    confirmSpy.mockRestore()

    await waitFor(() => {
      expect(screen.getByText('Environment terminated successfully')).toBeInTheDocument()
    })
  })

  it('does not show terminate button for non-sandbox or disconnected environments', async () => {
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
        containerId: null,
        createdAt: '2024-01-01T00:00:00Z',
        id: 'env-1',
        lastHeartbeat: null,
        name: 'My Connector',
        status: 'DISCONNECTED',
        teamId: 'team-1',
        type: 'CONNECTOR',
        updatedAt: '2024-01-01T00:00:00Z',
      },
      {
        containerId: 'container-stopped',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'env-stopped',
        lastHeartbeat: null,
        name: 'Stopped Sandbox',
        status: 'DISCONNECTED',
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

    expect(screen.queryByRole('button', { name: /terminate/i })).not.toBeInTheDocument()
  })

  it('shows connector created dialog with install command after creation', async () => {
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
      const elements = screen.getAllByText(/Environments/i)
      expect(elements.length).toBeGreaterThan(0)
    })

    // Find and click the add connector button
    const addButton =
      screen.queryByRole('button', { name: /add connector/i }) ||
      screen.queryByRole('button', { name: /add environment/i }) ||
      screen.queryByRole('button', { name: /new connector/i })

    if (addButton) {
      await user.click(addButton)

      // Fill in name
      await waitFor(() => {
        const nameInputs = screen.getAllByLabelText(/name/i)
        expect(nameInputs.length).toBeGreaterThan(0)
      })

      const nameInput = screen.getAllByLabelText(/name/i)[0]
      await user.type(nameInput, 'Test Connector')

      // Submit
      const submitButtons = screen.getAllByRole('button', { name: /create/i })
      await user.click(submitButtons[0])

      // Connector created dialog should appear with install command
      await waitFor(() => {
        expect(screen.getByText('Workspace Connector Created')).toBeInTheDocument()
      })
      expect(
        screen.getByText(/kratis-connector --mode=daemon/),
      ).toBeInTheDocument()
    }
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

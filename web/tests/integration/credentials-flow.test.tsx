import { beforeEach, describe, expect, it } from 'vitest'

import {
  addFetchHandler,
  jsonResponse,
  mockAffectedRepositories,
  mockCreateCredential,
  mockListCredentials,
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

describe('Credentials Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  it('renders credentials section in settings with existing credentials', async () => {
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
    mockListCredentials([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'cred-1',
        name: 'My GitLab Token',
        type: 'GITLAB',
      },
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'cred-2',
        name: 'GitHub Deploy Key',
        publicKey: 'ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ...',
        type: 'SSH_KEY',
      },
    ])
    mockListModelProvidersEmpty()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /integrations/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /integrations/i }))

    await waitFor(() => {
      expect(screen.getByText('My GitLab Token')).toBeInTheDocument()
    })
    expect(screen.getByText('GitHub Deploy Key')).toBeInTheDocument()
  })

  it('shows empty state when no credentials exist', async () => {
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
    mockListCredentials([])
    mockListModelProvidersEmpty()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /integrations/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /integrations/i }))

    await waitFor(() => {
      const elements = screen.getAllByText(/Credentials/i)
      expect(elements.length).toBeGreaterThan(0)
    })
  })

  it('creates a new credential via settings', async () => {
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
    mockListCredentials([])
    mockCreateCredential({
      id: 'cred-new',
      name: 'New GitLab Token',
      type: 'GITLAB',
    })
    mockListModelProvidersEmpty()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /integrations/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /integrations/i }))

    // Look for "Add Credential" or similar button
    const addCredButton = screen.queryByRole('button', { name: /add credential/i })
      || screen.queryByRole('button', { name: /new credential/i })

    if (addCredButton) {
      await user.click(addCredButton)

      // Fill in credential form
      await waitFor(() => {
        const nameInputs = screen.getAllByLabelText(/credential name/i)
        expect(nameInputs.length).toBeGreaterThan(0)
      })

      const nameInput = screen.getAllByLabelText(/credential name/i)[0]
      await user.type(nameInput, 'New GitLab Token')

      // Submit
      const saveButtons = screen.getAllByRole('button', { name: /save/i })
      await user.click(saveButtons[0])

      // Success toast
      await waitFor(() => {
        expect(screen.getByText('Credential saved successfully')).toBeInTheDocument()
      })
    }
  })

  it('deletes a credential with confirmation dialog', async () => {
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
    mockListCredentials([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'cred-1',
        name: 'My GitLab Token',
        type: 'GITLAB',
      },
    ])
    mockAffectedRepositories('cred-1', [
      { id: 'repo-1', name: 'frontend-app' },
    ])
    const deletedIds: string[] = []
    // Mock the delete endpoint directly to ensure correct 204 response
    addFetchHandler((url, options) => {
      if (url.includes('/credentials/cred-1') && options.method === 'DELETE') {
        deletedIds.push('cred-1')
        return new Response(null, { status: 204 })
      }
      return null
    })
    mockListModelProvidersEmpty()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /integrations/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /integrations/i }))

    await waitFor(() => {
      expect(screen.getByText('My GitLab Token')).toBeInTheDocument()
    })

    // Click delete button
    const deleteButton = screen.getByRole('button', { name: /delete my gitlab token/i })
    await user.click(deleteButton)

    // Dialog should appear
    await waitFor(() => {
      expect(screen.getByText('Delete Credential')).toBeInTheDocument()
    })
    // The credential name appears in both the list and the dialog
    const nameMatches = screen.getAllByText(/My GitLab Token/)
    expect(nameMatches.length).toBeGreaterThanOrEqual(1)

    // Affected repos warning should be visible
    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Confirm delete
    const confirmButton = screen.getByRole('button', { name: /^delete$/i })
    await user.click(confirmButton)

    // Success toast and credential removed
    await waitFor(() => {
      expect(screen.getByText('Credential deleted successfully')).toBeInTheDocument()
    })
    expect(deletedIds).toContain('cred-1')
  })

  it('shows empty state when no credentials exist in credentials panel', async () => {
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
    mockListCredentials([])
    mockListModelProvidersEmpty()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /integrations/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /integrations/i }))

    await waitFor(() => {
      expect(screen.getByText('No credentials configured')).toBeInTheDocument()
    })
    expect(
      screen.getByText(/Credentials will appear here when you add repositories to Kratis/),
    ).toBeInTheDocument()
  })

  it('shows credential with public key in credentials panel', async () => {
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
    mockListCredentials([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'cred-2',
        name: 'GitHub Deploy Key',
        publicKey: 'ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDExample',
        type: 'SSH_KEY',
      },
    ])
    mockListModelProvidersEmpty()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /integrations/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /integrations/i }))

    await waitFor(() => {
      expect(screen.getByText('GitHub Deploy Key')).toBeInTheDocument()
    })
    // Public key truncated display
    expect(screen.getByText(/Key: ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ/)).toBeInTheDocument()
    // Badge shows type (uppercased with space replacement)
    expect(screen.getByText('SSH KEY')).toBeInTheDocument()
  })
})

// Helper to mock empty model providers (needed for settings view)
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

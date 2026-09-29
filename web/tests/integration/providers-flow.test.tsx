import { within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type {
  CreateProviderData,
  EnvironmentProviderDto,
  UpdateProviderData,
} from '@/lib/provider-api'

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

describe('Providers Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  it('renders the providers list with pre-configured default provider', async () => {
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

    addFetchHandler((url, options) => {
      if (url.includes('/model-providers') && (!options.method || options.method === 'GET')) {
        return jsonResponse([])
      }
      return null
    })

    addFetchHandler((url, options) => {
      if (url.includes('/environment-providers') && (!options.method || options.method.toUpperCase() === 'GET')) {
        return jsonResponse([
          {
            dockerImage: 'kratis-runner-base:latest',
            id: 'provider-1',
            name: 'Default Docker Provider',
            teamId: 'team-1',
          },
        ])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getByText('Environment Providers')).toBeInTheDocument()
      expect(screen.getAllByText('Default Docker Provider').length).toBeGreaterThan(0)
    })
  })

  it('renders environment providers as mobile cards alongside the desktop table', async () => {
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

    addFetchHandler((url, options) => {
      if (url.includes('/model-providers') && (!options.method || options.method === 'GET')) {
        return jsonResponse([])
      }
      return null
    })

    addFetchHandler((url, options) => {
      if (url.includes('/environment-providers') && (!options.method || options.method.toUpperCase() === 'GET')) {
        return jsonResponse([
          {
            dockerImage: 'kratis-runner-base:latest',
            id: 'provider-1',
            name: 'Default Docker Provider',
            teamId: 'team-1',
          },
        ])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getByRole('table')).toBeInTheDocument()
    })
    const cards = screen.getByTestId('environment-provider-cards')
    expect(within(cards).getByText('Default Docker Provider')).toBeInTheDocument()
    expect(within(cards).getByText('kratis-runner-base:latest')).toBeInTheDocument()
    expect(
      within(cards).getByRole('button', { name: /edit default docker provider/i }),
    ).toBeInTheDocument()
  })

  it('edits an existing provider and dispatches a PUT request', async () => {
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

    addFetchHandler((url, options) => {
      if (url.includes('/model-providers') && (!options.method || options.method === 'GET')) {
        return jsonResponse([])
      }
      return null
    })

    addFetchHandler((url, options) => {
      if (url.includes('/environment-providers') && (!options.method || options.method.toUpperCase() === 'GET')) {
        return jsonResponse([
          {
            dockerImage: 'kratis-runner-base:latest',
            id: 'provider-1',
            name: 'Default Docker Provider',
            teamId: 'team-1',
          },
        ])
      }
      return null
    })

    let putPayload: null | UpdateProviderData = null
    addFetchHandler((url, options) => {
      if (url.includes('/environment-providers/provider-1') && options.method?.toUpperCase() === 'PUT') {
        putPayload = JSON.parse(options.body as string) as UpdateProviderData
        return jsonResponse({
          dockerImage: putPayload.dockerImage,
          id: 'provider-1',
          name: 'Default Docker Provider',
          teamId: 'team-1',
        })
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getByText('Environment Providers')).toBeInTheDocument()
      expect(screen.getAllByText('Default Docker Provider').length).toBeGreaterThan(0)
    })

    const row = within(screen.getByRole('table'))
      .getByText('Default Docker Provider')
      .closest('tr')
    const editButton = row?.querySelector('button')
    expect(editButton).toBeDefined()
    await user.click(editButton!)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Edit Environment Provider' })).toBeInTheDocument()
    })

    const dockerImageInput = screen.getByPlaceholderText('e.g., java:17 or kratis-runner-base:latest')
    await user.clear(dockerImageInput)
    await user.type(dockerImageInput, 'my-custom-image:v2')

    const saveButton = screen.getByRole('button', { name: 'Update Provider' })
    await user.click(saveButton)

    await waitFor(() => {
      expect(putPayload).toEqual({
        dockerImage: 'my-custom-image:v2',
        name: 'Default Docker Provider',
      })
    })
  })

  it('adds a new provider and dispatches a POST request', async () => {
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

    addFetchHandler((url, options) => {
      if (url.includes('/model-providers') && (!options.method || options.method === 'GET')) {
        return jsonResponse([])
      }
      return null
    })

    let postPayload: CreateProviderData | null = null
    addFetchHandler((url, options) => {
      if (url.includes('/environment-providers') && (!options.method || options.method.toUpperCase() === 'GET')) {
        const providers: EnvironmentProviderDto[] = [
          {
            dockerImage: 'kratis-runner-base:latest',
            id: 'provider-1',
            name: 'Default Docker Provider',
            teamId: 'team-1',
          },
        ]
        if (postPayload) {
          providers.push({
            dockerImage: postPayload.dockerImage,
            id: 'provider-2',
            name: postPayload.name,
            teamId: 'team-1',
          })
        }
        return jsonResponse(providers)
      }
      return null
    })

    addFetchHandler((url, options) => {
      if (url.includes('/environment-providers') && options.method?.toUpperCase() === 'POST') {
        postPayload = JSON.parse(options.body as string) as CreateProviderData
        return jsonResponse(
          {
            dockerImage: postPayload.dockerImage,
            id: 'provider-2',
            name: postPayload.name,
            teamId: 'team-1',
          },
          201
        )
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getByText('Environment Providers')).toBeInTheDocument()
      expect(screen.getAllByText('Default Docker Provider').length).toBeGreaterThan(0)
    })

    const addButton = screen.getByRole('button', { name: /add provider/i })
    await user.click(addButton)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Add Environment Provider' })).toBeInTheDocument()
    })

    const nameInput = screen.getByPlaceholderText('e.g., Java Docker Provider')
    await user.clear(nameInput)
    await user.type(nameInput, 'New Docker Provider')

    const dockerImageInput = screen.getByPlaceholderText('e.g., java:17 or kratis-runner-base:latest')
    await user.clear(dockerImageInput)
    await user.type(dockerImageInput, 'new-docker-image:latest')

    const submitButton = screen.getByRole('button', { name: 'Add Provider' })
    await user.click(submitButton)

    await waitFor(() => {
      expect(postPayload).toEqual({
        dockerImage: 'new-docker-image:latest',
        name: 'New Docker Provider',
      })
    })

    await waitFor(() => {
      expect(screen.getAllByText('New Docker Provider').length).toBeGreaterThan(0)
    })
  })

  it('deletes a provider and dispatches a DELETE request', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)

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

    addFetchHandler((url, options) => {
      if (url.includes('/model-providers') && (!options.method || options.method === 'GET')) {
        return jsonResponse([])
      }
      return null
    })

    addFetchHandler((url, options) => {
      if (url.includes('/environment-providers') && (!options.method || options.method.toUpperCase() === 'GET')) {
        return jsonResponse([
          {
            dockerImage: 'kratis-runner-base:latest',
            id: 'provider-1',
            name: 'Default Docker Provider',
            teamId: 'team-1',
          },
          {
            dockerImage: 'new-docker:latest',
            id: 'provider-2',
            name: 'New Docker Provider',
            teamId: 'team-1',
          },
        ])
      }
      return null
    })

    let deleteCalled = false
    addFetchHandler((url, options) => {
      if (url.includes('/environment-providers/provider-2') && options.method?.toUpperCase() === 'DELETE') {
        deleteCalled = true
        return new Response(null, { status: 204 })
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /runtime/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /runtime/i }))

    await waitFor(() => {
      expect(screen.getByText('Environment Providers')).toBeInTheDocument()
      expect(screen.getAllByText('New Docker Provider').length).toBeGreaterThan(0)
    })

    const newRow = within(screen.getByRole('table'))
      .getByText('New Docker Provider')
      .closest('tr')
    const buttons = newRow?.querySelectorAll('button') || []
    const deleteButton = buttons[1]
    expect(deleteButton).toBeDefined()
    await user.click(deleteButton)

    expect(window.confirm).toHaveBeenCalledWith('Are you sure you want to delete "New Docker Provider"?')

    await waitFor(() => {
      expect(deleteCalled).toBe(true)
    })
  })
})



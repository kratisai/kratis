import { within } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'

import {
  addFetchHandler,
  jsonResponse,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderWithRouter,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'

const mockSupportedTypes = [
  {
    defaultBaseUrl: 'https://api.openai.com/v1',
    description: 'OpenAI API (GPT-4, GPT-4o, etc.)',
    displayName: 'OpenAI',
    requiresApiKey: true,
    requiresBaseUrl: false,
    type: 'OPENAI',
  },
]

describe('Model Defaults Sync Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  it('reflects new team defaults without a page reload after adding a provider', async () => {
    let currentTeam: Record<string, unknown> = {
      createdAt: '2024-01-01T00:00:00Z',
      id: 'team-1',
      isDefault: true,
      name: 'Test Team',
      role: 'owner',
      tavilyApiKeyConfigured: false,
    }
    let providers: Array<Record<string, unknown>> = []

    addFetchHandler((url) => {
      if (url === '/api/v1/teams' || url === '/api/v1/teams/') {
        return jsonResponse([currentTeam])
      }
      return null
    })

    addFetchHandler((url, options) => {
      if (url === '/api/v1/teams/team-1' && (!options.method || options.method === 'GET')) {
        return jsonResponse({
          ...currentTeam,
          members: [],
        })
      }
      if (url === '/api/v1/teams/team-1' && options.method === 'PUT') {
        const body = JSON.parse((options.body as string) || '{}')
        currentTeam = { ...currentTeam, ...body }
        return jsonResponse(currentTeam)
      }
      return null
    })

    addFetchHandler((url, options) => {
      const match = url.match(/\/api\/v1\/model-providers\/teams\/[^/]+$/)
      if (match && (!options.method || options.method === 'GET')) {
        return jsonResponse(providers)
      }
      if (url.includes('/model-providers/teams/') && options.method === 'POST') {
        const body = JSON.parse((options.body as string) || '{}')
        const created = {
          createdAt: '2024-01-01T00:00:00Z',
          displayName: body.displayName,
          id: 'provider-new',
          isActive: true,
          models: body.models ?? [],
          providerType: body.providerType,
          teamId: 'team-1',
          updatedAt: '2024-01-01T00:00:00Z',
        }
        providers = [created]
        return jsonResponse(created, 201)
      }
      return null
    })

    addFetchHandler((url) => {
      if (url.includes('/model-providers/supported-types')) {
        return jsonResponse(mockSupportedTypes)
      }
      return null
    })

    addFetchHandler((url, options) => {
      if (url.includes('/model-providers/test-connection') && options.method === 'POST') {
        return jsonResponse({
          models: [
            { kind: 'CHAT', modelName: 'gpt-4' },
            { kind: 'EMBEDDING', modelName: 'text-embedding-ada-002' },
          ],
          success: true,
        })
      }
      return null
    })

    addFetchHandler((url) => {
      const match = url.match(/\/api\/v1\/model-providers\/[^/]+\/discover-models$/)
      if (match) {
        return jsonResponse([{ kind: 'EMBEDDING', modelName: 'text-embedding-ada-002' }])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /models/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /models/i }))

    await waitFor(() => {
      expect(screen.getByText('Default Models')).toBeInTheDocument()
    })

    const ingestionProviderSelect = screen.getByLabelText(/Ingestion Provider/i)
    await waitFor(() => {
      expect(ingestionProviderSelect).toHaveValue('')
    })

    await user.click(screen.getByRole('button', { name: /add provider/i }))
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Add Model Provider' })).toBeInTheDocument()
    })

    const dialog = () => within(screen.getByRole('dialog'))
    await user.click(dialog().getByRole('button', { name: /^OpenAI/ }))
    await user.click(dialog().getByRole('button', { name: /^next$/i }))

    await waitFor(() => {
      expect(dialog().getByLabelText('Name')).toBeInTheDocument()
    })
    await user.type(dialog().getByLabelText(/api key/i), 'sk-test-key')
    await user.click(dialog().getByRole('button', { name: /^next$/i }))

    await waitFor(() => {
      expect(dialog().getByRole('checkbox', { name: 'gpt-4' })).toBeInTheDocument()
    })
    await user.click(dialog().getByRole('checkbox', { name: 'gpt-4' }))
    await user.click(dialog().getByRole('button', { name: /^next$/i }))

    await waitFor(() => {
      expect(dialog().getAllByLabelText(/^Use OpenAI/)).toHaveLength(2)
    })
    await user.click(dialog().getByRole('button', { name: /add provider/i }))

    await waitFor(() => {
      expect(
        screen.queryByRole('heading', { name: 'Add Model Provider' }),
      ).not.toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.getByLabelText(/Ingestion Provider/i)).toHaveValue('provider-new')
    })
    expect(screen.getByLabelText(/Ingestion Model/i)).toHaveValue('gpt-4')
    expect(screen.getByLabelText(/Embedding Provider/i)).toHaveValue('provider-new')
  })
})

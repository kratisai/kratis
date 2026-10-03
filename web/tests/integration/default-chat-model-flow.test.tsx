import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ModelProviderDto, TeamDto } from '@/types/auth-types'

import { useUIStore } from '@/store/ui-store'

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

const team: TeamDto = {
  createdAt: '2024-01-01T00:00:00Z',
  id: 'team-1',
  isDefault: true,
  name: 'Test Team',
  role: 'owner',
  tavilyApiKeyConfigured: false,
}

function mockChatsEmpty() {
  addFetchHandler((url, options) => {
    const isChatList = url === '/api/v1/chats' || url.startsWith('/api/v1/chats?')
    if (isChatList && (!options.method || options.method === 'GET')) {
      return jsonResponse([])
    }
    return null
  })
}

function mockExecutionMetadataEmpty() {
  addFetchHandler((url, options) => {
    if (url.match(/\/api\/v1\/teams\/[^/]+\/harnesses$/) && (!options.method || options.method === 'GET')) {
      return jsonResponse([])
    }
    if (url.match(/\/api\/v1\/teams\/[^/]+\/environments$/) && (!options.method || options.method === 'GET')) {
      return jsonResponse([])
    }
    if (url.match(/\/api\/v1\/teams\/[^/]+\/environment-providers$/) && (!options.method || options.method === 'GET')) {
      return jsonResponse([])
    }
    return null
  })
}

function mockModelProviders(providers: ModelProviderDto[]) {
  addFetchHandler((url, options) => {
    const match = url.match(/\/api\/v1\/model-providers\/teams\/[^/]+$/)
    if (match && (!options.method || options.method === 'GET')) {
      return jsonResponse(providers)
    }
    return null
  })
}

function mockRepositoriesEmpty() {
  addFetchHandler((url, options) => {
    const match = url.match(/\/api\/v1\/teams\/[^/]+\/repositories$/)
    if (match && (!options.method || options.method === 'GET')) {
      return jsonResponse([])
    }
    return null
  })
}

function provider(
  id: string,
  models: Array<{ kind: 'CHAT' | 'EMBEDDING'; modelName: string }>,
  displayName = id,
): ModelProviderDto {
  return {
    createdAt: '2024-01-01T00:00:00Z',
    displayName,
    id,
    isActive: true,
    models,
    providerType: 'OPENAI',
    teamId: 'team-1',
    updatedAt: '2024-01-01T00:00:00Z',
  }
}

describe('Default Chat Model Selection', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    vi.stubGlobal('open', vi.fn())
    useUIStore.setState({ selectedModelName: null, selectedProviderId: null })
  })

  it('selects the first chat model when visiting /repos without visiting /ask', async () => {
    mockListTeams([team])
    mockModelProviders([provider('provider-1', [{ kind: 'CHAT', modelName: 'gpt-4' }])])
    mockRepositoriesEmpty()
    mockChatsEmpty()
    mockExecutionMetadataEmpty()

    setAuthenticated({ teamId: 'team-1' })
    renderIntegration(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })
    await waitFor(() => {
      expect(useUIStore.getState().selectedProviderId).toBe('provider-1')
      expect(useUIStore.getState().selectedModelName).toBe('gpt-4')
    })
  })

  it('keeps an existing selection when a new provider is added', async () => {
    const first = provider('provider-1', [{ kind: 'CHAT', modelName: 'gpt-4' }])
    const second = provider('provider-2', [{ kind: 'CHAT', modelName: 'claude-3' }], 'Second')
    let providers = [first]

    mockListTeams([team])
    mockRepositoriesEmpty()
    mockChatsEmpty()
    mockExecutionMetadataEmpty()
    addFetchHandler((url, options) => {
      const match = url.match(/\/api\/v1\/model-providers\/teams\/[^/]+$/)
      if (match && (!options.method || options.method === 'GET')) {
        return jsonResponse(providers)
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    renderIntegration(['/repos'])

    await waitFor(() => {
      expect(useUIStore.getState().selectedModelName).toBe('gpt-4')
    })

    useUIStore.getState().setSelectedModel('provider-1', 'gpt-4')
    providers = [first, second]

    useUIStore.getState().syncSelectedModel(providers)

    expect(useUIStore.getState().selectedProviderId).toBe('provider-1')
    expect(useUIStore.getState().selectedModelName).toBe('gpt-4')
  })

  it('selects a different model when the selected model is removed', async () => {
    const first = provider('provider-1', [{ kind: 'CHAT', modelName: 'gpt-4' }])
    const second = provider('provider-2', [{ kind: 'CHAT', modelName: 'claude-3' }], 'Second')

    mockListTeams([team])
    mockModelProviders([first, second])
    mockRepositoriesEmpty()
    mockChatsEmpty()
    mockExecutionMetadataEmpty()

    setAuthenticated({ teamId: 'team-1' })
    renderIntegration(['/repos'])

    await waitFor(() => {
      expect(useUIStore.getState().selectedModelName).toBe('gpt-4')
    })

    useUIStore.getState().syncSelectedModel([second])

    expect(useUIStore.getState().selectedProviderId).toBe('provider-2')
    expect(useUIStore.getState().selectedModelName).toBe('claude-3')
  })

  it('launches a chat from the Repos page once the default model is synced', async () => {
    mockListTeams([team])
    mockModelProviders([provider('provider-1', [{ kind: 'CHAT', modelName: 'gpt-4' }])])
    mockRepositoriesEmpty()
    mockChatsEmpty()
    mockExecutionMetadataEmpty()

    const createdChats: Array<Record<string, unknown>> = []
    addFetchHandler((url, options) => {
      const isExactSessionUrl = url.endsWith('/api/v1/chats') || url.includes('/api/v1/chats?')
      if (isExactSessionUrl && options.method === 'POST') {
        const body = JSON.parse((options.body as string) || '{}')
        createdChats.push(body)
        return jsonResponse({ createdAt: new Date().toISOString(), id: 'new-session-id' }, 202)
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    renderIntegration(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    await waitFor(() => {
      expect(useUIStore.getState().selectedModelName).toBe('gpt-4')
    })

    useUIStore.getState().setSelectedModel('provider-1', 'gpt-4')
    expect(createdChats).toHaveLength(0)
  })
})

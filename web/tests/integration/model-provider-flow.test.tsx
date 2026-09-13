import { within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ModelProviderDto, TeamDto } from '@/types/auth-types'

import { ModelProviderList } from '@/components/settings/model-provider-list'

import { addFetchHandler, jsonResponse, mockDiscoverModels, setupFetchMock } from '../support/test-fetch-mocks'
import {
  renderWithProviders,
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
  {
    defaultBaseUrl: 'https://api.anthropic.com',
    description: 'Anthropic Claude API',
    displayName: 'Anthropic',
    requiresApiKey: true,
    requiresBaseUrl: false,
    type: 'ANTHROPIC',
  },
  {
    defaultBaseUrl: null,
    description: 'Azure OpenAI Service',
    displayName: 'Azure OpenAI',
    requiresApiKey: true,
    requiresBaseUrl: true,
    type: 'AZURE_OPENAI',
  },
  {
    defaultBaseUrl: 'http://localhost:11434',
    description: 'Local Ollama instance',
    displayName: 'Ollama',
    requiresApiKey: false,
    requiresBaseUrl: true,
    type: 'OLLAMA',
  },
  {
    defaultBaseUrl: null,
    description: 'AWS Bedrock',
    displayName: 'AWS Bedrock',
    requiresApiKey: true,
    requiresBaseUrl: true,
    type: 'BEDROCK',
  },
  {
    defaultBaseUrl: null,
    description: 'Custom OpenAI-compatible API endpoint',
    displayName: 'Other (OpenAI-compatible)',
    requiresApiKey: true,
    requiresBaseUrl: true,
    type: 'OTHER',
  },
]

const mockProviders: ModelProviderDto[] = [
  {
    baseUrl: 'https://api.openai.com/v1',
    createdAt: '2024-01-01T00:00:00Z',
    displayName: 'OpenAI Provider',
    id: 'provider-1',
    isActive: true,
    models: [
      { kind: 'CHAT', modelName: 'gpt-4o' },
      { kind: 'CHAT', modelName: 'gpt-4o-mini' },
    ],
    providerType: 'OPENAI',
    teamId: 'team-1',
    updatedAt: '2024-01-01T00:00:00Z',
  },
]

const mockTeam: TeamDto = {
  createdAt: '2024-01-01T00:00:00Z',
  id: 'team-1',
  isDefault: true,
  name: 'Test Team',
  role: 'owner',
  tavilyApiKeyConfigured: false,
}

describe('Model Provider Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  function mockListModelProviders(providers: ModelProviderDto[] = []) {
    addFetchHandler((url, options) => {
      const match = url.match(/\/api\/v1\/model-providers\/teams\/[^/]+$/)
      if (match && (!options.method || options.method === 'GET')) {
        return jsonResponse(providers)
      }
      return null
    })
    addFetchHandler((url) => {
      if (url.includes('/model-providers/supported-types')) {
        return jsonResponse(mockSupportedTypes)
      }
      return null
    })
  }

  function mockCreateModelProvider(shouldFail = false) {
    const calls: Array<Record<string, unknown>> = []
    addFetchHandler((url, options) => {
      if (url.includes('/model-providers/teams/') && options.method === 'POST') {
        if (shouldFail) {
          return jsonResponse({ message: 'Provider name already exists' }, 409)
        }
        const body = JSON.parse((options.body as string) || '{}')
        calls.push(body)
        return jsonResponse(
          {
            createdAt: '2024-01-01T00:00:00Z',
            displayName: body.displayName,
            id: 'provider-new',
            isActive: true,
            models: body.models ?? [],
            providerType: body.providerType,
            teamId: 'team-1',
            updatedAt: '2024-01-01T00:00:00Z',
          },
          201,
        )
      }
      return null
    })
    return calls
  }

  function mockUpdateModelProvider() {
    const calls: Array<Record<string, unknown>> = []
    addFetchHandler((url, options) => {
      if (url.includes('/model-providers/') && url.includes('/') && options.method === 'PUT') {
        const body = JSON.parse((options.body as string) || '{}')
        calls.push(body)
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          displayName: body.displayName ?? 'Updated Provider',
          id: 'provider-1',
          isActive: true,
          models: body.models ?? [],
          providerType: 'OPENAI',
          teamId: 'team-1',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      return null
    })
    return calls
  }

  function mockUpdateTeam() {
    const calls: Array<Record<string, unknown>> = []
    addFetchHandler((url, options) => {
      if (url.includes('/teams/') && options.method === 'PUT') {
        const body = JSON.parse((options.body as string) || '{}')
        calls.push(body)
        return jsonResponse({ ...mockTeam, ...body })
      }
      return null
    })
    return calls
  }

  function mockTestConnection(
    result: { error?: string; models?: string[]; success: boolean },
    shouldFail = false,
  ) {
    let calls = 0
    addFetchHandler((url, options) => {
      if (url.includes('/model-providers/test-connection') && options.method === 'POST') {
        calls += 1
        if (shouldFail) {
          return jsonResponse({ message: 'Connection failed' }, 500)
        }
        return jsonResponse(result)
      }
      return null
    })
    return { getCalls: () => calls }
  }

  const renderList = (team: TeamDto = mockTeam, isOwner = true) =>
    renderWithProviders(<ModelProviderList isOwner={isOwner} team={team} />)

  const openWizard = async (user: ReturnType<typeof renderList>['user']) => {
    await waitFor(() => {
      expect(screen.getByText('No model providers configured')).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /add provider/i }))
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Add Model Provider' })).toBeInTheDocument()
    })
  }

  const dialog = () => within(screen.getByRole('dialog'))

  const selectProvider = async (user: ReturnType<typeof renderList>['user'], name: RegExp) => {
    await user.click(dialog().getByRole('button', { name }))
    await waitFor(() => {
      expect(dialog().getByRole('button', { name: /^next$/i })).toBeEnabled()
    })
    await user.click(dialog().getByRole('button', { name: /^next$/i }))
    await waitFor(() => {
      expect(dialog().getByLabelText('Name')).toBeInTheDocument()
    })
  }

  const connectFromConfigure = async (
    user: ReturnType<typeof renderList>['user'],
    name?: string,
    apiKey?: string,
  ) => {
    if (name !== undefined) {
      const nameInput = dialog().getByLabelText('Name')
      await user.clear(nameInput)
      await user.type(nameInput, name)
    }
    if (apiKey !== undefined) {
      await user.type(dialog().getByLabelText(/api key/i), apiKey)
    }
    await user.click(dialog().getByRole('button', { name: /^next$/i }))
  }

  it('shows empty state when no providers exist', async () => {
    mockListModelProviders([])
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderList()

    await waitFor(() => {
      expect(screen.getByText('No model providers configured')).toBeInTheDocument()
    })
  })

  it('displays list of providers when loaded', async () => {
    mockListModelProviders(mockProviders)
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderList()

    await waitFor(() => {
      expect(screen.getAllByText('OpenAI Provider').length).toBeGreaterThan(0)
      expect(screen.getAllByText('OPENAI').length).toBeGreaterThan(0)
    })
  })

  it('renders model providers as mobile cards alongside the desktop table', async () => {
    mockListModelProviders(mockProviders)
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderList()

    await waitFor(() => {
      expect(screen.getByRole('table')).toBeInTheDocument()
    })
    const cards = screen.getByTestId('model-provider-cards')
    expect(within(cards).getByText('OpenAI Provider')).toBeInTheDocument()
    expect(within(cards).getByText('https://api.openai.com/v1')).toBeInTheDocument()
    expect(within(cards).getByRole('button', { name: /edit openai provider/i })).toBeInTheDocument()
    expect(
      within(cards).getByRole('button', { name: /delete openai provider/i }),
    ).toBeInTheDocument()
  })

  it('opens add provider wizard and filters providers by search', async () => {
    mockListModelProviders([])
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList()
    await openWizard(user)

    await user.type(dialog().getByLabelText('Filter providers'), 'oll')
    await waitFor(() => {
      expect(dialog().getByRole('button', { name: /^Ollama/ })).toBeInTheDocument()
      expect(dialog().queryByRole('button', { name: /^OpenAI/ })).not.toBeInTheDocument()
    })
  })

  it('requires a provider selection before Next on step 1', async () => {
    mockListModelProviders([])
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList()
    await openWizard(user)

    expect(dialog().getByRole('button', { name: /^next$/i })).toBeDisabled()
  })

  it('creates a provider via the wizard and sets team defaults', async () => {
    mockListModelProviders([])
    const createCalls = mockCreateModelProvider()
    const teamCalls = mockUpdateTeam()
    const connection = mockTestConnection({
      models: ['gpt-4', 'gpt-3.5-turbo', 'text-embedding-ada-002'],
      success: true,
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList()
    await openWizard(user)

    // Step 1: choose provider; name is auto-suggested
    await selectProvider(user, /^OpenAI/)
    expect(dialog().getByLabelText('Name')).toHaveValue('OpenAI')

    // Step 2: no connection request until Next is clicked
    expect(connection.getCalls()).toBe(0)

    // Step 3: models appear and none are pre-selected
    await connectFromConfigure(user, undefined, 'sk-test-key')
    await waitFor(() => {
      expect(dialog().getByRole('checkbox', { name: 'gpt-4' })).toBeInTheDocument()
    })
    expect(dialog().getAllByRole('checkbox').every((box) => box.getAttribute('data-state') === 'unchecked')).toBe(
      true,
    )

    // Next is disabled with nothing selected
    expect(dialog().getByRole('button', { name: /^next$/i })).toBeDisabled()
    await user.click(dialog().getByRole('checkbox', { name: 'gpt-4' }))
    expect(dialog().getByRole('button', { name: /^next$/i })).toBeEnabled()
    await user.click(dialog().getByRole('button', { name: /^next$/i }))

    // Step 4: team has no defaults, so this provider is pre-selected for both slots
    await waitFor(() => {
      expect(dialog().getAllByLabelText(/^Use OpenAI/)).toHaveLength(2)
    })
    expect(dialog().getAllByLabelText(/^Use OpenAI/)[0]).toBeChecked()
    expect(dialog().getAllByLabelText(/^Use OpenAI/)[1]).toBeChecked()
    await user.click(dialog().getByRole('button', { name: /add provider/i }))

    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: 'Add Model Provider' })).not.toBeInTheDocument()
    })

    expect(createCalls).toHaveLength(1)
    expect(createCalls[0]).toMatchObject({
      apiKey: 'sk-test-key',
      displayName: 'OpenAI',
      models: [{ kind: 'CHAT', modelName: 'gpt-4' }],
      providerType: 'OPENAI',
    })
    expect(teamCalls).toHaveLength(1)
    expect(teamCalls[0]).toMatchObject({
      embeddingModel: 'text-embedding-ada-002',
      embeddingProvider: 'provider-new',
      ingestionModel: 'gpt-4',
      ingestionProvider: 'provider-new',
    })
  })

  it('keeps current team defaults when they already exist', async () => {
    mockListModelProviders([])
    const createCalls = mockCreateModelProvider()
    const teamCalls = mockUpdateTeam()
    mockTestConnection({ models: ['gpt-4'], success: true })

    const teamWithDefaults: TeamDto = {
      ...mockTeam,
      embeddingModel: 'text-embedding-3-small',
      embeddingProvider: 'provider-9',
      ingestionModel: 'gpt-4o',
      ingestionProvider: 'provider-9',
    }

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList(teamWithDefaults)
    await openWizard(user)

    await selectProvider(user, /^OpenAI/)
    await connectFromConfigure(user, undefined, 'sk-test-key')
    await waitFor(() => {
      expect(dialog().getByRole('checkbox', { name: 'gpt-4' })).toBeInTheDocument()
    })
    await user.click(dialog().getByRole('checkbox', { name: 'gpt-4' }))
    await user.click(dialog().getByRole('button', { name: /^next$/i }))

    // Both slots default to "Keep current"
    await waitFor(() => {
      expect(dialog().getAllByLabelText(/^Keep current default/)).toHaveLength(2)
    })
    expect(dialog().getAllByLabelText(/^Use OpenAI/)[0]).not.toBeChecked()
    expect(dialog().getAllByLabelText(/^Use OpenAI/)[1]).not.toBeChecked()
    await user.click(dialog().getByRole('button', { name: /add provider/i }))

    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: 'Add Model Provider' })).not.toBeInTheDocument()
    })
    expect(createCalls).toHaveLength(1)
    expect(teamCalls).toHaveLength(0)
  })

  it('shows a retryable error when the team defaults update fails', async () => {
    mockListModelProviders([])
    const createCalls = mockCreateModelProvider()
    mockTestConnection({ models: ['gpt-4'], success: true })
    let teamUpdateAttempts = 0
    addFetchHandler((url, options) => {
      if (url.includes('/teams/') && options.method === 'PUT') {
        teamUpdateAttempts += 1
        if (teamUpdateAttempts === 1) {
          return jsonResponse({ message: 'Server error' }, 500)
        }
        return jsonResponse({ ...mockTeam })
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList()
    await openWizard(user)

    await selectProvider(user, /^OpenAI/)
    await connectFromConfigure(user, undefined, 'sk-test-key')
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
      expect(dialog().getByText(/updating team defaults failed/i)).toBeInTheDocument()
    })
    expect(dialog().getByRole('heading', { name: 'Add Model Provider' })).toBeInTheDocument()

    // Retry only re-runs the defaults update, not the provider creation
    await user.click(dialog().getByRole('button', { name: /retry defaults/i }))
    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: 'Add Model Provider' })).not.toBeInTheDocument()
    })
    expect(createCalls).toHaveLength(1)
    expect(teamUpdateAttempts).toBe(2)
  })

  it('shows the server validation message when the connection test rejects the API key', async () => {
    mockListModelProviders([])
    addFetchHandler((url, options) => {
      if (url.includes('/model-providers/test-connection') && options.method === 'POST') {
        return jsonResponse(
          {
            errors: { apiKey: 'API key must be at most 8192 characters' },
            message: 'Validation failed',
          },
          400,
        )
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList()
    await openWizard(user)

    await selectProvider(user, /^OpenAI/)
    await connectFromConfigure(user, undefined, 'sk-test-key')

    await waitFor(() => {
      expect(dialog().getByText('API key must be at most 8192 characters')).toBeInTheDocument()
    })
    expect(dialog().getByRole('heading', { name: 'Add Model Provider' })).toBeInTheDocument()
  })

  it('shows the server validation message when creating the provider is rejected', async () => {
    mockListModelProviders([])
    mockTestConnection({ models: ['gpt-4'], success: true })
    addFetchHandler((url, options) => {
      if (url.includes('/model-providers/teams/') && options.method === 'POST') {
        return jsonResponse(
          {
            errors: { apiKey: 'API key must be at most 8192 characters' },
            message: 'Validation failed',
          },
          400,
        )
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList()
    await openWizard(user)

    await selectProvider(user, /^OpenAI/)
    await connectFromConfigure(user, undefined, 'sk-test-key')
    await waitFor(() => {
      expect(dialog().getByRole('checkbox', { name: 'gpt-4' })).toBeInTheDocument()
    })
    await user.click(dialog().getByRole('checkbox', { name: 'gpt-4' }))
    await user.click(dialog().getByRole('button', { name: /^next$/i }))
    await waitFor(() => {
      expect(dialog().getAllByLabelText(/^Use OpenAI/)).toHaveLength(2)
    })
    await user.click(dialog().getByRole('button', { name: /add provider/i }))

    // The wizard returns to the configure step so the message is shown against the field
    await waitFor(() => {
      expect(dialog().getByText('API key must be at most 8192 characters')).toBeInTheDocument()
    })
    expect(dialog().getByLabelText('Name')).toBeInTheDocument()
  })

  it('filters models within a section on step 3', async () => {
    mockListModelProviders([])
    mockTestConnection({
      models: ['gpt-4', 'gpt-4-turbo', 'gpt-4o', 'text-embedding-ada-002'],
      success: true,
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList()
    await openWizard(user)

    await selectProvider(user, /^OpenAI/)
    await connectFromConfigure(user, undefined, 'sk-test-key')
    await waitFor(() => {
      expect(dialog().getByRole('checkbox', { name: 'gpt-4' })).toBeInTheDocument()
    })

    await user.type(dialog().getByLabelText('Filter chat models'), 'gpt-4o')
    await waitFor(() => {
      expect(dialog().getByRole('checkbox', { name: 'gpt-4o' })).toBeInTheDocument()
      expect(dialog().queryByRole('checkbox', { name: 'gpt-4' })).not.toBeInTheDocument()
    })
  })

  it('connects keyless Ollama with a pre-filled base URL', async () => {
    mockListModelProviders([])
    mockTestConnection({ models: ['llama3:latest'], success: true })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList()
    await openWizard(user)

    await selectProvider(user, /^Ollama/)

    // No API key field for keyless providers; base URL pre-filled
    expect(dialog().queryByLabelText(/api key/i)).not.toBeInTheDocument()
    expect(dialog().getByLabelText(/base url/i)).toHaveValue('http://localhost:11434')

    await user.click(dialog().getByRole('button', { name: /^next$/i }))
    await waitFor(() => {
      expect(dialog().getByRole('checkbox', { name: 'llama3:latest' })).toBeInTheDocument()
    })
  })

  it('requires an API key for key-based providers', async () => {
    mockListModelProviders([])
    const connection = mockTestConnection({ models: ['gpt-4'], success: true })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList()
    await openWizard(user)

    await selectProvider(user, /^OpenAI/)
    await user.click(dialog().getByRole('button', { name: /^next$/i }))

    await waitFor(() => {
      expect(dialog().getByText('API Key is required')).toBeInTheDocument()
    })
    expect(connection.getCalls()).toBe(0)
  })

  it('shows connection errors and retries on the next click', async () => {
    mockListModelProviders([])
    const connection = mockTestConnection({ error: 'Invalid API key', success: false })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList()
    await openWizard(user)

    await selectProvider(user, /^OpenAI/)
    await connectFromConfigure(user, undefined, 'bad-key')

    await waitFor(() => {
      expect(dialog().getByText('Invalid API key')).toBeInTheDocument()
    })
    expect(dialog().getByRole('heading', { name: 'Add Model Provider' })).toBeInTheDocument()

    // Retry fires a fresh connection request
    const callsBeforeRetry = connection.getCalls()
    await user.click(dialog().getByRole('button', { name: /^next$/i }))
    await waitFor(() => {
      expect(connection.getCalls()).toBe(callsBeforeRetry + 1)
    })
  })

  it('treats zero discovered models as an error', async () => {
    mockListModelProviders([])
    mockTestConnection({ models: [], success: true })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList()
    await openWizard(user)

    await selectProvider(user, /^OpenAI/)
    await connectFromConfigure(user, undefined, 'sk-test-key')

    await waitFor(() => {
      expect(dialog().getByText('Connected, but no models were discovered')).toBeInTheDocument()
    })
  })

  it('preserves form values when navigating back and forward', async () => {
    mockListModelProviders([])
    mockTestConnection({ models: ['gpt-4'], success: true })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList()
    await openWizard(user)

    await selectProvider(user, /^OpenAI/)
    await connectFromConfigure(user, 'My Custom Name', 'sk-test-key')
    await waitFor(() => {
      expect(dialog().getByRole('checkbox', { name: 'gpt-4' })).toBeInTheDocument()
    })

    // Back to configure, then forward again — values must survive
    await user.click(dialog().getByRole('button', { name: /^back$/i }))
    expect(dialog().getByLabelText('Name')).toHaveValue('My Custom Name')
    expect(dialog().getByLabelText(/api key/i)).toHaveValue('sk-test-key')

    // Back to provider step and forward: cache is reused, no second connection call
    await user.click(dialog().getByRole('button', { name: /^back$/i }))
    await user.click(dialog().getByRole('button', { name: /^next$/i }))
    await user.click(dialog().getByRole('button', { name: /^next$/i }))
    await waitFor(() => {
      expect(dialog().getByRole('checkbox', { name: 'gpt-4' })).toBeInTheDocument()
    })
  })

  it('edits a provider with stored models pre-selected', async () => {
    mockListModelProviders(mockProviders)
    const updateCalls = mockUpdateModelProvider()
    mockUpdateTeam()
    mockDiscoverModels([
      { kind: 'CHAT', modelName: 'gpt-4o' },
      { kind: 'CHAT', modelName: 'gpt-4o-mini' },
      { kind: 'EMBEDDING', modelName: 'text-embedding-3-small' },
    ])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList()
    await waitFor(() => {
      expect(screen.getAllByText('OpenAI Provider').length).toBeGreaterThan(0)
    })

    await user.click(within(screen.getByRole('table')).getByRole('button', { name: /edit openai provider/i }))
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Edit Model Provider' })).toBeInTheDocument()
    })

    // Edit mode opens directly on Configure with the type locked
    expect(dialog().getByText('OPENAI')).toBeInTheDocument()
    expect(dialog().getByText(/provider type cannot be changed/i)).toBeInTheDocument()
    expect(dialog().getByLabelText('Name')).toHaveValue('OpenAI Provider')

    // Change the name, then connect via the stored credentials
    await connectFromConfigure(user, 'Updated Provider')
    await waitFor(() => {
      expect(dialog().getByRole('checkbox', { name: 'gpt-4o' })).toBeInTheDocument()
    })

    // Stored models are pre-selected
    expect(dialog().getByRole('checkbox', { name: 'gpt-4o' })).toHaveAttribute('data-state', 'checked')
    expect(dialog().getByRole('checkbox', { name: 'gpt-4o-mini' })).toHaveAttribute('data-state', 'checked')

    await user.click(dialog().getByRole('button', { name: /^next$/i }))

    await waitFor(() => {
      expect(dialog().getAllByLabelText(/^Use Updated Provider/)).toHaveLength(2)
    })
    await user.click(dialog().getByRole('button', { name: /^update$/i }))

    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: 'Edit Model Provider' })).not.toBeInTheDocument()
    })

    expect(updateCalls).toHaveLength(1)
    expect(updateCalls[0]).toMatchObject({
      displayName: 'Updated Provider',
      models: [
        { kind: 'CHAT', modelName: 'gpt-4o' },
        { kind: 'CHAT', modelName: 'gpt-4o-mini' },
      ],
    })
  })

  it('does not show action buttons for non-owners', async () => {
    mockListModelProviders(mockProviders)
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderList(mockTeam, false)

    await waitFor(() => {
      expect(screen.getAllByText('OpenAI Provider').length).toBeGreaterThan(0)
    })
    expect(screen.queryByRole('button', { name: /add provider/i })).not.toBeInTheDocument()
  })

  it('deletes a provider with confirmation', async () => {
    let deleteCalled = false
    addFetchHandler((url, options) => {
      if (url.includes('/model-providers/teams/') && options.method === 'DELETE') {
        deleteCalled = true
        return jsonResponse(null, 204)
      }
      return null
    })
    mockListModelProviders(mockProviders)
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderList()
    await waitFor(() => {
      expect(screen.getAllByText('OpenAI Provider').length).toBeGreaterThan(0)
    })

    await user.click(
      within(screen.getByRole('table')).getByRole('button', { name: /delete openai provider/i }),
    )
    expect(window.confirm).toHaveBeenCalled()
    await waitFor(() => {
      expect(deleteCalled).toBe(true)
    })
  })
})

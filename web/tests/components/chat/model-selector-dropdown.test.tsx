import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ModelSelectorDropdown } from '@/components/chat/model-selector-dropdown'
import * as useModelProvidersModule from '@/hooks/use-model-providers'
import { useAuthStore } from '@/store/auth-store'
import { useUIStore } from '@/store/ui-store'

vi.mock('@/hooks/use-model-providers')

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })
}

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = createTestQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>,
  )
}

describe('ModelSelectorDropdown', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useUIStore.setState({ selectedModelName: null })
    useAuthStore.setState({ currentTeamId: 'team-1', isAuthenticated: true })
  })

  it('shows disabled state when providers are loading', () => {
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: undefined,
      isError: false,
      isLoading: true,
    } as never)

    renderWithProviders(<ModelSelectorDropdown />)
    expect(screen.getByText('No models')).toBeInTheDocument()
  })

  it('shows disabled state when no providers exist', () => {
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: [],
      isError: false,
      isLoading: false,
    } as never)

    renderWithProviders(<ModelSelectorDropdown />)
    expect(screen.getByText('No models')).toBeInTheDocument()
  })

  it('shows model name when providers are available', () => {
    const mockProviders = [
      {
        baseUrl: 'https://api.openai.com/v1',
        createdAt: '2024-01-01T00:00:00Z',
        displayName: 'OpenAI',
        id: 'provider-1',
        isActive: true,
        models: [
          { kind: 'CHAT' as const, modelName: 'gpt-4' },
          { kind: 'CHAT' as const, modelName: 'gpt-3.5-turbo' },
        ],
        providerType: 'OPENAI' as const,
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ]
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: mockProviders,
      isError: false,
      isLoading: false,
    } as never)

    renderWithProviders(<ModelSelectorDropdown />)
    expect(screen.getByText('gpt-4')).toBeInTheDocument()
  })

  it('shows selected model name', () => {
    const mockProviders = [
      {
        baseUrl: 'https://api.openai.com/v1',
        createdAt: '2024-01-01T00:00:00Z',
        displayName: 'OpenAI',
        id: 'provider-1',
        isActive: true,
        models: [
          { kind: 'CHAT' as const, modelName: 'gpt-4' },
          { kind: 'CHAT' as const, modelName: 'gpt-3.5-turbo' },
        ],
        providerType: 'OPENAI' as const,
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ]
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: mockProviders,
      isError: false,
      isLoading: false,
    } as never)

    useUIStore.setState({ selectedModelName: 'gpt-3.5-turbo' })

    renderWithProviders(<ModelSelectorDropdown />)
    expect(screen.getByText('gpt-3.5-turbo')).toBeInTheDocument()
  })

  it('opens dropdown and shows provider sections', async () => {
    const mockProviders = [
      {
        baseUrl: 'https://api.openai.com/v1',
        createdAt: '2024-01-01T00:00:00Z',
        displayName: 'OpenAI',
        id: 'provider-1',
        isActive: true,
        models: [{ kind: 'CHAT' as const, modelName: 'gpt-4' }],
        providerType: 'OPENAI' as const,
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
      },
      {
        baseUrl: 'https://api.anthropic.com',
        createdAt: '2024-01-01T00:00:00Z',
        displayName: 'Anthropic',
        id: 'provider-2',
        isActive: true,
        models: [{ kind: 'CHAT' as const, modelName: 'claude-3' }],
        providerType: 'ANTHROPIC' as const,
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ]
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: mockProviders,
      isError: false,
      isLoading: false,
    } as never)

    const user = userEvent.setup()
    renderWithProviders(<ModelSelectorDropdown />)

    const button = screen.getByRole('button')
    await user.click(button)

    await waitFor(() => {
      expect(screen.getByText('OpenAI')).toBeInTheDocument()
    })
    expect(screen.getByText('Anthropic')).toBeInTheDocument()
    expect(screen.getByText('claude-3')).toBeInTheDocument()
  })

  it('selects a model when clicking dropdown item', async () => {
    const mockProviders = [
      {
        baseUrl: 'https://api.openai.com/v1',
        createdAt: '2024-01-01T00:00:00Z',
        displayName: 'OpenAI',
        id: 'provider-1',
        isActive: true,
        models: [
          { kind: 'CHAT' as const, modelName: 'gpt-4' },
          { kind: 'CHAT' as const, modelName: 'gpt-3.5-turbo' },
        ],
        providerType: 'OPENAI' as const,
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ]
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: mockProviders,
      isError: false,
      isLoading: false,
    } as never)

    const user = userEvent.setup()
    renderWithProviders(<ModelSelectorDropdown />)

    const button = screen.getByRole('button')
    await user.click(button)

    await waitFor(() => {
      expect(screen.getByText('gpt-3.5-turbo')).toBeInTheDocument()
    })
    await user.click(screen.getByText('gpt-3.5-turbo'))

    expect(useUIStore.getState().selectedModelName).toBe('gpt-3.5-turbo')
  })

  it('shows checkmark next to selected model', async () => {
    const mockProviders = [
      {
        baseUrl: 'https://api.openai.com/v1',
        createdAt: '2024-01-01T00:00:00Z',
        displayName: 'OpenAI',
        id: 'provider-1',
        isActive: true,
        models: [
          { kind: 'CHAT' as const, modelName: 'gpt-4' },
          { kind: 'CHAT' as const, modelName: 'gpt-3.5-turbo' },
        ],
        providerType: 'OPENAI' as const,
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ]
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: mockProviders,
      isError: false,
      isLoading: false,
    } as never)

    useUIStore.setState({ selectedModelName: 'gpt-4' })

    renderWithProviders(<ModelSelectorDropdown />)

    expect(screen.getByText('gpt-4')).toBeInTheDocument()
  })

  it('skips providers with no chat models', async () => {
    const mockProviders = [
      {
        baseUrl: 'https://api.openai.com/v1',
        createdAt: '2024-01-01T00:00:00Z',
        displayName: 'Empty Provider',
        id: 'provider-empty',
        isActive: true,
        models: [],
        providerType: 'OPENAI' as const,
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
      },
      {
        baseUrl: 'https://api.openai.com/v1',
        createdAt: '2024-01-01T00:00:00Z',
        displayName: 'OpenAI',
        id: 'provider-1',
        isActive: true,
        models: [{ kind: 'CHAT' as const, modelName: 'gpt-4' }],
        providerType: 'OPENAI' as const,
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ]
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: mockProviders,
      isError: false,
      isLoading: false,
    } as never)

    const user = userEvent.setup()
    renderWithProviders(<ModelSelectorDropdown />)

    const button = screen.getByRole('button')
    await user.click(button)

    await waitFor(() => {
      expect(screen.queryByText('Empty Provider')).not.toBeInTheDocument()
    })
    expect(screen.getByText('OpenAI')).toBeInTheDocument()
  })

  it('supports controlled mode without mutating useUIStore', async () => {
    const mockProviders = [
      {
        baseUrl: 'https://api.openai.com/v1',
        createdAt: '2024-01-01T00:00:00Z',
        displayName: 'OpenAI',
        id: 'provider-1',
        isActive: true,
        models: [
          { kind: 'CHAT' as const, modelName: 'gpt-4' },
          { kind: 'CHAT' as const, modelName: 'gpt-3.5-turbo' },
        ],
        providerType: 'OPENAI' as const,
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ]
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: mockProviders,
      isError: false,
      isLoading: false,
    } as never)

    useUIStore.setState({ selectedModelName: 'original-model', selectedProviderId: 'original-prov' })
    const onSelectModel = vi.fn()

    const user = userEvent.setup()
    renderWithProviders(
      <ModelSelectorDropdown
        modelName="gpt-4"
        onSelectModel={onSelectModel}
        providerId="provider-1"
      />,
    )

    expect(screen.getByText('gpt-4')).toBeInTheDocument()

    const button = screen.getByRole('button')
    await user.click(button)

    await waitFor(() => {
      expect(screen.getByText('gpt-3.5-turbo')).toBeInTheDocument()
    })
    await user.click(screen.getByText('gpt-3.5-turbo'))

    expect(onSelectModel).toHaveBeenCalledWith('provider-1', 'gpt-3.5-turbo')
    expect(useUIStore.getState().selectedModelName).toBe('original-model')
    expect(useUIStore.getState().selectedProviderId).toBe('original-prov')
  })

  it('renders triggerVariant="form" with form styling', () => {
    const mockProviders = [
      {
        baseUrl: 'https://api.openai.com/v1',
        createdAt: '2024-01-01T00:00:00Z',
        displayName: 'OpenAI',
        id: 'provider-1',
        isActive: true,
        models: [{ kind: 'CHAT' as const, modelName: 'gpt-4' }],
        providerType: 'OPENAI' as const,
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ]
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: mockProviders,
      isError: false,
      isLoading: false,
    } as never)

    renderWithProviders(
      <ModelSelectorDropdown
        modelName="gpt-4"
        providerId="provider-1"
        triggerVariant="form"
      />,
    )

    const button = screen.getByRole('button')
    expect(button).toHaveClass('h-9', 'w-full', 'border-input')
  })

  it('auto-selects first available model in controlled mode when none is selected', async () => {
    const mockProviders = [
      {
        baseUrl: 'https://api.openai.com/v1',
        createdAt: '2024-01-01T00:00:00Z',
        displayName: 'OpenAI',
        id: 'provider-1',
        isActive: true,
        models: [{ kind: 'CHAT' as const, modelName: 'gpt-4' }],
        providerType: 'OPENAI' as const,
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ]
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: mockProviders,
      isError: false,
      isLoading: false,
    } as never)

    const onSelectModel = vi.fn()
    renderWithProviders(
      <ModelSelectorDropdown
        modelName={null}
        onSelectModel={onSelectModel}
        providerId={null}
      />,
    )

    await waitFor(() => {
      expect(onSelectModel).toHaveBeenCalledWith('provider-1', 'gpt-4')
    })
  })
})

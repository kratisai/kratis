import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { CanvasDocument } from '@/types/canvas-types'

import { ExecutionLaunchDialog } from '@/components/canvas/execution-launch-dialog'
import * as useModelProvidersModule from '@/hooks/use-model-providers'
import { useAuthStore } from '@/store/auth-store'
import { useCanvasStore } from '@/store/canvas-store'
import { useChatStore } from '@/store/chat-store'
import { useUIStore } from '@/store/ui-store'

// Polyfills for Radix UI Select / Dropdown in jsdom
beforeEach(() => {
  if (typeof Element.prototype.hasPointerCapture !== 'function') {
    Element.prototype.hasPointerCapture = () => false
  }
  if (typeof Element.prototype.scrollIntoView !== 'function') {
    Element.prototype.scrollIntoView = () => {}
  }
})

vi.mock('@/hooks/use-providers', () => ({
  useProviders: vi.fn(() => ({ data: [{ id: 'prov-docker', name: 'Docker Host' }] })),
}))

vi.mock('@/hooks/use-environments', () => ({
  useEnvironments: vi.fn(() => ({
    data: [{ id: 'env-1', name: 'Test Env', status: 'CONNECTED', type: 'CONNECTOR' }],
  })),
}))

vi.mock('@/hooks/use-harnesses', () => ({
  useHarnesses: vi.fn(() => ({ data: [{ name: 'OpenCode', value: 'OPENCODE' }] })),
}))

vi.mock('@/hooks/use-model-providers', () => ({
  useModelProviders: vi.fn(),
}))

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
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

const mockProviders = [
  {
    baseUrl: 'https://api.openai.com/v1',
    createdAt: '2024-01-01T00:00:00Z',
    displayName: 'OpenAI',
    id: 'prov-openai',
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
    id: 'prov-anthropic',
    isActive: true,
    models: [{ kind: 'CHAT' as const, modelName: 'claude-3' }],
    providerType: 'ANTHROPIC' as const,
    teamId: 'team-1',
    updatedAt: '2024-01-01T00:00:00Z',
  },
]

function makeDoc(overrides: Partial<CanvasDocument> = {}): CanvasDocument {
  return {
    canvasType: 'SPEC',
    chatId: 'chat-1',
    content: '',
    documentId: 'doc-1',
    isNewRepo: false,
    title: 'Spec Doc',
    version: 1,
    ...overrides,
  }
}

describe('ExecutionLaunchDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({ currentTeamId: 'team-1', isAuthenticated: true })
    useChatStore.setState({ currentChatId: 'chat-1' })
    useCanvasStore.setState({
      canvases: { 'chat-1': [makeDoc()] },
    })
    useUIStore.setState({
      selectedModelName: 'gpt-4',
      selectedProviderId: 'prov-openai',
    })
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: mockProviders,
      isError: false,
      isLoading: false,
    } as never)
  })

  it('pre-fills model provider and model name from useUIStore', () => {
    renderWithProviders(
      <ExecutionLaunchDialog
        onLaunch={vi.fn()}
        onOpenChange={vi.fn()}
        open={true}
      />,
    )

    expect(screen.getByText('Which model?')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'gpt-4' })).toBeInTheDocument()
  })

  it('updates trigger display and does not mutate useUIStore when switching model', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <ExecutionLaunchDialog
        onLaunch={vi.fn()}
        onOpenChange={vi.fn()}
        open={true}
      />,
    )

    const modelTrigger = screen.getByRole('button', { name: 'gpt-4' })
    await user.click(modelTrigger)

    const claudeOption = await screen.findByText('claude-3')
    await user.click(claudeOption)

    // Trigger updates to show newly selected model
    expect(screen.getByRole('button', { name: 'claude-3' })).toBeInTheDocument()

    // Global store remains unchanged
    expect(useUIStore.getState().selectedModelName).toBe('gpt-4')
    expect(useUIStore.getState().selectedProviderId).toBe('prov-openai')
  })

  it('disables launch when no model is selected or available', async () => {
    const user = userEvent.setup()
    useUIStore.setState({ selectedModelName: null, selectedProviderId: null })
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: [],
      isError: false,
      isLoading: false,
    } as never)

    renderWithProviders(
      <ExecutionLaunchDialog
        onLaunch={vi.fn()}
        onOpenChange={vi.fn()}
        open={true}
      />,
    )

    const triggers = screen.getAllByRole('combobox')
    await user.click(triggers[0])
    await user.click(screen.getByText('Docker (Docker Host)'))

    await user.click(triggers[1])
    await user.click(screen.getByText('OpenCode'))

    const launchButton = screen.getByRole('button', { name: /launch/i })
    expect(launchButton).toBeDisabled()
  })

  it('dispatches onLaunch with chosen modelName and modelProviderId', async () => {
    const user = userEvent.setup()
    const onLaunch = vi.fn()

    renderWithProviders(
      <ExecutionLaunchDialog
        onLaunch={onLaunch}
        onOpenChange={vi.fn()}
        open={true}
      />,
    )

    // Select target
    const triggers = screen.getAllByRole('combobox')
    await user.click(triggers[0])
    await user.click(screen.getByText('Docker (Docker Host)'))

    // Select harness
    await user.click(triggers[1])
    await user.click(screen.getByText('OpenCode'))

    // Switch model to claude-3
    const modelTrigger = screen.getByRole('button', { name: 'gpt-4' })
    await user.click(modelTrigger)
    const claudeOption = await screen.findByText('claude-3')
    await user.click(claudeOption)

    // Click launch
    const launchButton = screen.getByRole('button', { name: /launch/i })
    expect(launchButton).not.toBeDisabled()
    await user.click(launchButton)

    expect(onLaunch).toHaveBeenCalledWith({
      harness: 'OPENCODE',
      modelName: 'claude-3',
      modelProviderId: 'prov-anthropic',
      name: 'Spawn new Docker Container (Docker Host)',
      providerId: 'prov-docker',
    })

    // UI store remains isolated
    expect(useUIStore.getState().selectedModelName).toBe('gpt-4')
    expect(useUIStore.getState().selectedProviderId).toBe('prov-openai')
  })

  it('never collects a credential at launch', async () => {
    const user = userEvent.setup()
    const onLaunch = vi.fn()

    renderWithProviders(
      <ExecutionLaunchDialog
        onLaunch={onLaunch}
        onOpenChange={vi.fn()}
        open={true}
      />,
    )

    expect(screen.queryByText(/credential/i)).not.toBeInTheDocument()

    const triggers = screen.getAllByRole('combobox')
    await user.click(triggers[0])
    await user.click(screen.getByText('Docker (Docker Host)'))

    await user.click(triggers[1])
    await user.click(screen.getByText('OpenCode'))

    const launchButton = screen.getByRole('button', { name: /launch/i })
    expect(launchButton).not.toBeDisabled()
    await user.click(launchButton)

    expect(onLaunch).toHaveBeenCalledWith({
      harness: 'OPENCODE',
      modelName: 'gpt-4',
      modelProviderId: 'prov-openai',
      name: 'Spawn new Docker Container (Docker Host)',
      providerId: 'prov-docker',
    })
    expect(onLaunch.mock.calls[0][0]).not.toHaveProperty('credentialId')
  })

  it('resets local model selection to useUIStore defaults when reopened', async () => {
    const user = userEvent.setup()
    const { rerender } = renderWithProviders(
      <ExecutionLaunchDialog
        onLaunch={vi.fn()}
        onOpenChange={vi.fn()}
        open={true}
      />,
    )

    // Switch model to claude-3
    const modelTrigger = screen.getByRole('button', { name: 'gpt-4' })
    await user.click(modelTrigger)
    const claudeOption = await screen.findByText('claude-3')
    await user.click(claudeOption)
    expect(screen.getByRole('button', { name: 'claude-3' })).toBeInTheDocument()

    // Close and reopen
    rerender(
      <QueryClientProvider client={createTestQueryClient()}>
        <ExecutionLaunchDialog
          onLaunch={vi.fn()}
          onOpenChange={vi.fn()}
          open={false}
        />
      </QueryClientProvider>,
    )
    rerender(
      <QueryClientProvider client={createTestQueryClient()}>
        <ExecutionLaunchDialog
          onLaunch={vi.fn()}
          onOpenChange={vi.fn()}
          open={true}
        />
      </QueryClientProvider>,
    )

    // Pre-filled from useUIStore again
    expect(screen.getByRole('button', { name: 'gpt-4' })).toBeInTheDocument()
  })
})

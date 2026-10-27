import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { SandboxExecutionDto } from '@/lib/execution-api'

import { useExecutionEnvironment } from '@/hooks/use-executions'
import * as executionApi from '@/lib/execution-api'

vi.mock('@/lib/execution-api', () => ({
  haltExecution: vi.fn(),
  listChatExecutions: vi.fn(),
}))

const createWrapper = () => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
}

function createExecution(overrides: Partial<SandboxExecutionDto> = {}): SandboxExecutionDto {
  return {
    chatId: 'chat-1',
    id: 'exec-1',
    startedAt: '2026-01-01T00:00:00Z',
    status: 'RUNNING',
    ...overrides,
  }
}

describe('useExecutionEnvironment', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('reports the execution environment as sleeping when it is sleeping', async () => {
    vi.mocked(executionApi.listChatExecutions).mockResolvedValue([
      createExecution({ environmentId: 'env-sleeping', environmentStatus: 'SLEEPING' }),
    ])

    const { result } = renderHook(() => useExecutionEnvironment('chat-1', 'exec-1'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current).toMatchObject({
      environmentId: 'env-sleeping',
      environmentStatus: 'SLEEPING',
      isAwake: false,
      isSleeping: true,
    })
  })

  it('reports the execution environment as awake when it is connected', async () => {
    vi.mocked(executionApi.listChatExecutions).mockResolvedValue([
      createExecution({ environmentId: 'env-awake', environmentStatus: 'CONNECTED' }),
    ])

    const { result } = renderHook(() => useExecutionEnvironment('chat-1', 'exec-1'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current).toMatchObject({
      environmentId: 'env-awake',
      environmentStatus: 'CONNECTED',
      isAwake: true,
      isSleeping: false,
    })
  })

  it('treats an execution without an environment status as awake', async () => {
    vi.mocked(executionApi.listChatExecutions).mockResolvedValue([
      createExecution({ environmentId: 'env-unknown' }),
    ])

    const { result } = renderHook(() => useExecutionEnvironment('chat-1', 'exec-1'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current).toMatchObject({
      environmentId: 'env-unknown',
      environmentStatus: undefined,
      isAwake: true,
      isSleeping: false,
    })
  })

  it('does not fetch and reports a neutral state when chatId is missing', async () => {
    const { result } = renderHook(() => useExecutionEnvironment(null, 'exec-1'), {
      wrapper: createWrapper(),
    })

    expect(result.current).toMatchObject({
      environmentId: null,
      environmentStatus: undefined,
      isAwake: true,
      isSleeping: false,
    })
    expect(executionApi.listChatExecutions).not.toHaveBeenCalled()
  })

  it('reports a neutral state when executionId is missing', async () => {
    vi.mocked(executionApi.listChatExecutions).mockResolvedValue([
      createExecution({ environmentId: 'env-sleeping', environmentStatus: 'SLEEPING' }),
    ])

    const { result } = renderHook(() => useExecutionEnvironment('chat-1', undefined), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current).toMatchObject({
      environmentId: null,
      environmentStatus: undefined,
      isAwake: true,
      isSleeping: false,
    })
  })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { SandboxExecutionDto } from '@/lib/execution-api'

import { executionDisplayStatus, terminateExecution } from '@/lib/execution-api'
import { useAuthStore } from '@/store/auth-store'

type HasEnvironmentFields = 'environmentId' extends keyof SandboxExecutionDto
  ? 'environmentStatus' extends keyof SandboxExecutionDto
    ? true
    : never
  : never
const hasEnvironmentFields: HasEnvironmentFields = true
void hasEnvironmentFields

describe('terminateExecution', () => {
  beforeEach(() => {
    useAuthStore.setState({ accessToken: 'test-token' })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 202 })))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    useAuthStore.setState({ accessToken: null })
  })

  it('POSTs to the chat execution terminate endpoint', async () => {
    await terminateExecution('chat-1', 'exec-1')

    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/chats/chat-1/executions/exec-1/terminate',
      expect.objectContaining({ method: 'POST' }),
    )
  })

  it('sends the access token', async () => {
    await terminateExecution('chat-1', 'exec-1')

    expect(fetch).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer test-token' }),
      }),
    )
  })

  it('rejects with an ApiError when the request fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: 'nope' }), { status: 409 })),
    )

    await expect(terminateExecution('chat-1', 'exec-1')).rejects.toMatchObject({
      message: 'nope',
    })
  })
})

describe('executionDisplayStatus', () => {
  function execution(overrides: Partial<SandboxExecutionDto>): SandboxExecutionDto {
    return {
      chatId: 'chat-1',
      id: 'exec-1',
      startedAt: '2026-01-01T00:00:00.000Z',
      status: 'RUNNING',
      ...overrides,
    }
  }

  it('reports SLEEPING for a non-completed run whose environment is asleep', () => {
    expect(
      executionDisplayStatus(execution({ environmentStatus: 'SLEEPING', status: 'FAILED' })),
    ).toBe('SLEEPING')
    expect(
      executionDisplayStatus(execution({ environmentStatus: 'SLEEPING', status: 'IDLE' })),
    ).toBe('SLEEPING')
  })

  it('keeps COMPLETED even when the environment is asleep', () => {
    expect(
      executionDisplayStatus(execution({ environmentStatus: 'SLEEPING', status: 'COMPLETED' })),
    ).toBe('COMPLETED')
  })

  it('returns the execution status when the environment is awake', () => {
    expect(
      executionDisplayStatus(execution({ environmentStatus: 'CONNECTED', status: 'RUNNING' })),
    ).toBe('RUNNING')
    expect(executionDisplayStatus(execution({ status: 'FAILED' }))).toBe('FAILED')
  })
})

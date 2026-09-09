import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { SandboxExecutionDto } from '@/lib/execution-api'

import { terminateExecution } from '@/lib/execution-api'
import { useAuthStore } from '@/store/auth-store'

type EnvIdRemoved = 'environmentId' extends keyof SandboxExecutionDto ? never : true
const envIdRemoved: EnvIdRemoved = true
void envIdRemoved

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

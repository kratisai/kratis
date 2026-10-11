import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AllowAllPermissionsToggle } from '@/components/topic/allow-all-permissions-toggle'
import { useAuthStore } from '@/store/auth-store'

import { createMockChat } from '../../support/test-factories'

let chatList: Array<ReturnType<typeof createMockChat>> = []
let patchBody: null | Record<string, unknown> = null

beforeEach(() => {
  useAuthStore.setState({ currentTeamId: 'team-1', isAuthenticated: true })
  chatList = []
  patchBody = null
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: Request | string | URL, init?: RequestInit) => {
      const url =
        typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
      const options = init ?? {}
      if (url.startsWith('/api/v1/chats?') && (!options.method || options.method === 'GET')) {
        return new Response(JSON.stringify(chatList), {
          headers: { 'Content-Type': 'application/json' },
          status: 200,
        })
      }
      if (/\/api\/v1\/chats\/[^/]+\/permissions$/.test(url) && options.method === 'PATCH') {
        patchBody = JSON.parse(String(options.body))
        return new Response(null, { status: 204 })
      }
      return new Response(null, { status: 404 })
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function renderToggle(chatId = 'chat-1') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <AllowAllPermissionsToggle chatId={chatId} />
    </QueryClientProvider>,
  )
}

describe('AllowAllPermissionsToggle', () => {
  it('reflects an enabled topic permission mode', async () => {
    chatList = [createMockChat({ allowAllPermissions: true, id: 'chat-1' })]

    renderToggle()

    const button = await screen.findByRole('button', {
      name: /auto approve permission requests in this topic/i,
    })
    await waitFor(() => expect(button).toHaveAttribute('aria-pressed', 'true'))
  })

  it('defaults to disabled when the topic has prompts enabled', async () => {
    chatList = [createMockChat({ allowAllPermissions: false, id: 'chat-1' })]

    renderToggle()

    const button = await screen.findByRole('button', {
      name: /auto approve permission requests in this topic/i,
    })
    await waitFor(() => expect(button).toHaveAttribute('aria-pressed', 'false'))
  })

  it('sends a PATCH to enable allow-all when toggled on', async () => {
    const user = userEvent.setup()
    chatList = [createMockChat({ allowAllPermissions: false, id: 'chat-1' })]

    renderToggle()
    const button = await screen.findByRole('button', {
      name: /auto approve permission requests in this topic/i,
    })
    await user.click(button)

    await waitFor(() => expect(patchBody).toEqual({ allowAllPermissions: true }))
  })
})

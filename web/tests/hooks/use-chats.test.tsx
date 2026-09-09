import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { useChats } from '@/hooks/use-chats'

// Mock the chat API
vi.mock('@/lib/chat-api', () => ({
  fetchChats: vi.fn(),
}))

import * as chatApi from '@/lib/chat-api'

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

const mockChats = [
  {
    createdAt: '2024-01-01T00:00:00Z',
    createdByDisplayName: 'User 1',
    id: 'session-1',
    teamId: 'team-1',
    title: 'Test Session 1',
    updatedAt: '2024-01-01T00:00:00Z',
  },
]

describe('useChats', () => {
  it('fetches chats on mount', async () => {
    vi.mocked(chatApi.fetchChats).mockResolvedValue(mockChats)

    const { result } = renderHook(() => useChats('team-1', 'mine'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(chatApi.fetchChats).toHaveBeenCalledWith('team-1', 'mine', 'active')
    expect(result.current.data).toEqual(mockChats)
  })

  it('refetches when team changes', async () => {
    vi.mocked(chatApi.fetchChats).mockResolvedValue(mockChats)

    const { rerender } = renderHook(
      ({ teamId }) => useChats(teamId, 'mine'),
      { initialProps: { teamId: 'team-1' }, wrapper: createWrapper() },
    )

    await waitFor(() => expect(chatApi.fetchChats).toHaveBeenCalledWith('team-1', 'mine', 'active'))

    rerender({ teamId: 'team-2' })

    await waitFor(() => expect(chatApi.fetchChats).toHaveBeenCalledWith('team-2', 'mine', 'active'))
  })

  it('filters by mine', async () => {
    vi.mocked(chatApi.fetchChats).mockResolvedValue(mockChats)

    renderHook(() => useChats('team-1', 'mine'), { wrapper: createWrapper() })

    await waitFor(() => expect(chatApi.fetchChats).toHaveBeenCalledWith('team-1', 'mine', 'active'))
  })

  it('filters by all', async () => {
    vi.mocked(chatApi.fetchChats).mockResolvedValue(mockChats)

    renderHook(() => useChats('team-1', 'all'), { wrapper: createWrapper() })

    await waitFor(() => expect(chatApi.fetchChats).toHaveBeenCalledWith('team-1', 'all', 'active'))
  })

  it('shows loading state', () => {
    vi.mocked(chatApi.fetchChats).mockReturnValue(new Promise(() => {}))

    const { result } = renderHook(() => useChats('team-1', 'mine'), {
      wrapper: createWrapper(),
    })

    expect(result.current.isLoading).toBe(true)
  })

  it('handles empty state', async () => {
    vi.mocked(chatApi.fetchChats).mockResolvedValue([])

    const { result } = renderHook(() => useChats('team-1', 'mine'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.data).toEqual([]))
  })

  it('handles API error', async () => {
    vi.mocked(chatApi.fetchChats).mockRejectedValue(new Error('API Error'))

    const { result } = renderHook(() => useChats('team-1', 'mine'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})

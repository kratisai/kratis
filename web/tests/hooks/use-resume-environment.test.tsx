import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { toast } from 'sonner'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useResumeEnvironment } from '@/hooks/use-environments'
import * as environmentApi from '@/lib/environment-api'
import { useAuthStore } from '@/store/auth-store'

vi.mock('@/lib/environment-api', () => ({
  createConnector: vi.fn(),
  deleteEnvironment: vi.fn(),
  getEnvironments: vi.fn(),
  resumeEnvironment: vi.fn(),
  updateEnvironment: vi.fn(),
}))

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn() },
}))

const createClient = () => new QueryClient({ defaultOptions: { queries: { retry: false } } })

const wrapperFor = (queryClient: QueryClient) =>
  function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }

describe('useResumeEnvironment', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({ currentTeamId: 'team-1', isAuthenticated: true })
  })

  it('resumes the environment for the current team and refreshes the environment list', async () => {
    vi.mocked(environmentApi.resumeEnvironment).mockResolvedValue({
      id: 'env-1',
      status: 'PENDING_CONNECT',
    } as Awaited<ReturnType<typeof environmentApi.resumeEnvironment>>)
    const queryClient = createClient()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const { result } = renderHook(() => useResumeEnvironment(), {
      wrapper: wrapperFor(queryClient),
    })

    result.current.mutate('env-1')

    await waitFor(() =>
      expect(toast.success).toHaveBeenCalledWith('Environment resumed successfully'),
    )
    expect(environmentApi.resumeEnvironment).toHaveBeenCalledWith('team-1', 'env-1')
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['environments', 'team-1'] })
  })

  it('shows the server message when the suspend snapshot has expired (410)', async () => {
    vi.mocked(environmentApi.resumeEnvironment).mockRejectedValue(
      new Error('Environment was terminated after its suspend snapshot expired'),
    )
    const { result } = renderHook(() => useResumeEnvironment(), {
      wrapper: wrapperFor(createClient()),
    })

    result.current.mutate('env-1')

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith(
        'Environment was terminated after its suspend snapshot expired',
      ),
    )
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('refuses to resume when no team is selected', async () => {
    useAuthStore.setState({ currentTeamId: null })
    const { result } = renderHook(() => useResumeEnvironment(), {
      wrapper: wrapperFor(createClient()),
    })

    result.current.mutate('env-1')

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('No team selected'))
    expect(environmentApi.resumeEnvironment).not.toHaveBeenCalled()
  })
})

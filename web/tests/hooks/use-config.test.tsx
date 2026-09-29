import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useGitHubAppInfo, useInstallationInfo } from '@/hooks/use-config'
import * as configApi from '@/lib/config-api'

vi.mock('@/lib/config-api', () => ({
  fetchGitHubAppInfo: vi.fn(),
  fetchInstallationInfo: vi.fn(),
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

describe('use-config hooks', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('useInstallationInfo fetches installation info', async () => {
    const mockInfo = { installId: 'test-install-uuid', version: '1.0.0' }
    vi.mocked(configApi.fetchInstallationInfo).mockResolvedValue(mockInfo)

    const { result } = renderHook(() => useInstallationInfo(), {
      wrapper: createWrapper(),
    })

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true)
    })

    expect(result.current.data).toEqual(mockInfo)
    expect(configApi.fetchInstallationInfo).toHaveBeenCalledOnce()
  })

  it('useGitHubAppInfo fetches GitHub App configuration', async () => {
    const mockAppInfo = {
      appId: '12345',
      appName: 'Kratis App',
      enabled: true,
      installationUrl: 'https://github.com/apps/kratis/installations/new',
    }
    vi.mocked(configApi.fetchGitHubAppInfo).mockResolvedValue(mockAppInfo)

    const { result } = renderHook(() => useGitHubAppInfo(), {
      wrapper: createWrapper(),
    })

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true)
    })

    expect(result.current.data).toEqual(mockAppInfo)
    expect(configApi.fetchGitHubAppInfo).toHaveBeenCalledOnce()
  })
})

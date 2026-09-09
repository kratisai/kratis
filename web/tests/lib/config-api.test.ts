import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchGitHubAppInfo, fetchInstallationInfo } from '@/lib/config-api'
import { useAuthStore } from '@/store/auth-store'

vi.mock('@/store/auth-store', () => ({
  useAuthStore: {
    getState: vi.fn(),
  },
}))

describe('config-api', () => {
  const mockFetch = vi.fn()
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    globalThis.fetch = mockFetch
    vi.mocked(useAuthStore.getState).mockReturnValue({
      accessToken: 'mock-access-token',
    } as unknown as ReturnType<typeof useAuthStore.getState>)
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
    vi.resetAllMocks()
  })

  describe('fetchInstallationInfo', () => {
    it('should fetch installation info with auth header', async () => {
      const mockInfo = { installId: 'test-install-id-123', version: '1.0.0' }
      mockFetch.mockResolvedValueOnce({
        json: async () => mockInfo,
        ok: true,
      })

      const result = await fetchInstallationInfo()

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/config/installation',
        expect.objectContaining({
          headers: expect.objectContaining({
            Authorization: 'Bearer mock-access-token',
            'Content-Type': 'application/json',
          }),
        }),
      )
      expect(result).toEqual(mockInfo)
    })
  })

  describe('fetchGitHubAppInfo', () => {
    it('should fetch github app info with auth header', async () => {
      const mockAppInfo = {
        appId: '123',
        appName: 'Kratis',
        enabled: true,
        installationUrl: 'https://github.com/apps/kratis',
      }
      mockFetch.mockResolvedValueOnce({
        json: async () => mockAppInfo,
        ok: true,
      })

      const result = await fetchGitHubAppInfo()

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/config/github-app',
        expect.objectContaining({
          headers: expect.objectContaining({
            Authorization: 'Bearer mock-access-token',
            'Content-Type': 'application/json',
          }),
        }),
      )
      expect(result).toEqual(mockAppInfo)
    })
  })
})

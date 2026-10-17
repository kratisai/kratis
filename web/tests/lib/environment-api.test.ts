import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  createConnector,
  deleteEnvironment,
  getEnvironments,
  resumeEnvironment,
} from '@/lib/environment-api'
import { useAuthStore } from '@/store/auth-store'

vi.mock('@/store/auth-store', () => ({
  useAuthStore: {
    getState: vi.fn(),
  },
}))

describe('environment-api', () => {
  const mockFetch = vi.fn()
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    globalThis.fetch = mockFetch
    vi.mocked(useAuthStore.getState).mockReturnValue({
      accessToken: 'mock-token',
    } as unknown as ReturnType<typeof useAuthStore.getState>)
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
    vi.resetAllMocks()
  })

  describe('createConnector', () => {
    it('should create a connector successfully', async () => {
      const mockResponse = {
        environment: { id: 'env-1', name: 'Test Env' },
        installCommand: 'curl -sL ...',
      }
      mockFetch.mockResolvedValueOnce({
        json: async () => mockResponse,
        ok: true,
      })

      const result = await createConnector('team-1', { name: 'Test Env' })
      expect(result).toEqual(mockResponse)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/teams/team-1/environments',
        expect.objectContaining({
          headers: expect.objectContaining({
            Authorization: 'Bearer mock-token',
            'Content-Type': 'application/json',
          }),
          method: 'POST',
        }),
      )
    })

    it('should throw an error on failure', async () => {
      mockFetch.mockResolvedValueOnce({
        json: async () => ({ message: 'Bad Request' }),
        ok: false,
        status: 400,
      })

      await expect(createConnector('team-1', { name: 'Test Env' })).rejects.toThrow('Bad Request')
    })
  })

  describe('deleteEnvironment', () => {
    it('should delete an environment successfully', async () => {
      mockFetch.mockResolvedValueOnce({
        json: async () => ({}),
        ok: true,
      })

      await deleteEnvironment('team-1', 'env-1')
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/teams/team-1/environments/env-1',
        expect.objectContaining({
          method: 'DELETE',
        }),
      )
    })
  })

  describe('getEnvironments', () => {
    it('should get environments successfully', async () => {
      const mockEnvs = [{ id: 'env-1', name: 'Test Env' }]
      mockFetch.mockResolvedValueOnce({
        json: async () => mockEnvs,
        ok: true,
      })

      const result = await getEnvironments('team-1')
      expect(result).toEqual(mockEnvs)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/teams/team-1/environments',
        expect.objectContaining({
          headers: expect.objectContaining({
            Authorization: 'Bearer mock-token',
          }),
        }),
      )
    })
  })

  describe('resumeEnvironment', () => {
    it('should POST to the resume endpoint and return the environment', async () => {
      const resumed = { id: 'env-1', status: 'PENDING_CONNECT' }
      mockFetch.mockResolvedValueOnce({ json: async () => resumed, ok: true })

      await expect(resumeEnvironment('team-1', 'env-1')).resolves.toEqual(resumed)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/teams/team-1/environments/env-1/resume',
        expect.objectContaining({ method: 'POST' }),
      )
    })

    it('should surface the expired-snapshot error (410)', async () => {
      mockFetch.mockResolvedValueOnce({
        json: async () => ({
          message: 'Environment was terminated after its suspend snapshot expired',
        }),
        ok: false,
        status: 410,
      })

      await expect(resumeEnvironment('team-1', 'env-1')).rejects.toMatchObject({
        message: 'Environment was terminated after its suspend snapshot expired',
        status: 410,
      })
    })

    it('should surface a not-sleeping error (400)', async () => {
      mockFetch.mockResolvedValueOnce({
        json: async () => ({ message: 'Environment is not sleeping' }),
        ok: false,
        status: 400,
      })

      await expect(resumeEnvironment('team-1', 'env-1')).rejects.toThrow(
        'Environment is not sleeping',
      )
    })
  })
})

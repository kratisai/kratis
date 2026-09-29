import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  createHitlRule,
  deleteHitlRule,
  listHitlRules,
} from '@/lib/hitl-rule-api'
import { useAuthStore } from '@/store/auth-store'

vi.mock('@/store/auth-store', () => ({
  useAuthStore: {
    getState: vi.fn(),
  },
}))

describe('hitl-rule-api', () => {
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

  describe('listHitlRules', () => {
    it('should list HITL rules successfully', async () => {
      const mockRules = [
        {
          action: 'ALLOW',
          commandRoot: 'npm test',
          createdAt: '2026-09-06T12:00:00Z',
          createdByName: 'Alice',
          createdByUserId: 'user-1',
          id: 'rule-1',
          ruleType: 'EXACT',
          teamId: 'team-1',
        },
      ]
      mockFetch.mockResolvedValueOnce({
        json: async () => mockRules,
        ok: true,
      })

      const result = await listHitlRules('team-1')
      expect(result).toEqual(mockRules)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/teams/team-1/hitl-rules',
        expect.objectContaining({
          headers: expect.objectContaining({
            Authorization: 'Bearer mock-token',
          }),
        }),
      )
    })

    it('should throw ApiError on failure', async () => {
      mockFetch.mockResolvedValueOnce({
        json: async () => ({ message: 'Forbidden' }),
        ok: false,
        status: 403,
      })

      await expect(listHitlRules('team-1')).rejects.toThrow('Forbidden')
    })
  })

  describe('createHitlRule', () => {
    it('should create a HITL rule successfully', async () => {
      const mockRule = {
        action: 'DENY',
        commandRoot: 'rm -rf',
        createdAt: '2026-09-06T12:00:00Z',
        createdByName: 'Bob',
        createdByUserId: 'user-2',
        id: 'rule-2',
        ruleType: 'PREFIX_WILD',
        teamId: 'team-1',
      }
      mockFetch.mockResolvedValueOnce({
        json: async () => mockRule,
        ok: true,
      })

      const result = await createHitlRule('team-1', {
        action: 'DENY',
        commandRoot: 'rm -rf',
        ruleType: 'PREFIX_WILD',
      })
      expect(result).toEqual(mockRule)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/teams/team-1/hitl-rules',
        expect.objectContaining({
          body: JSON.stringify({
            action: 'DENY',
            commandRoot: 'rm -rf',
            ruleType: 'PREFIX_WILD',
          }),
          headers: expect.objectContaining({
            Authorization: 'Bearer mock-token',
            'Content-Type': 'application/json',
          }),
          method: 'POST',
        }),
      )
    })

    it('should throw ApiError on conflict', async () => {
      mockFetch.mockResolvedValueOnce({
        json: async () => ({ message: 'Rule already exists' }),
        ok: false,
        status: 409,
      })

      await expect(
        createHitlRule('team-1', {
          action: 'ALLOW',
          commandRoot: 'git status',
          ruleType: 'EXACT',
        }),
      ).rejects.toThrow('Rule already exists')
    })
  })

  describe('deleteHitlRule', () => {
    it('should delete a HITL rule successfully', async () => {
      mockFetch.mockResolvedValueOnce({
        ok: true,
      })

      await deleteHitlRule('team-1', 'rule-1')
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/teams/team-1/hitl-rules/rule-1',
        expect.objectContaining({
          headers: expect.objectContaining({
            Authorization: 'Bearer mock-token',
          }),
          method: 'DELETE',
        }),
      )
    })

    it('should throw ApiError on not found', async () => {
      mockFetch.mockResolvedValueOnce({
        json: async () => ({ message: 'HITL rule not found' }),
        ok: false,
        status: 404,
      })

      await expect(deleteHitlRule('team-1', 'rule-999')).rejects.toThrow(
        'HITL rule not found',
      )
    })
  })
})

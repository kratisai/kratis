import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { HitlRuleDto } from '@/types/hitl-rule-types'

import {
  useCreateHitlRule,
  useDeleteHitlRule,
  useHitlRules,
} from '@/hooks/use-hitl-rules'
import * as hitlRuleApi from '@/lib/hitl-rule-api'
import { useAuthStore } from '@/store/auth-store'

vi.mock('@/lib/hitl-rule-api', () => ({
  createHitlRule: vi.fn(),
  deleteHitlRule: vi.fn(),
  listHitlRules: vi.fn(),
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

const mockRules: HitlRuleDto[] = [
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

describe('use-hitl-rules hooks', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({
      currentTeamId: 'team-1',
      isAuthenticated: true,
      user: {
        email: 'alice@example.com',
        id: 'user-1',
        name: 'Alice',
      },
    })
  })

  it('useHitlRules fetches rules for current team', async () => {
    vi.mocked(hitlRuleApi.listHitlRules).mockResolvedValue(mockRules)

    const { result } = renderHook(() => useHitlRules(), {
      wrapper: createWrapper(),
    })

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true)
    })

    expect(result.current.data).toEqual(mockRules)
    expect(hitlRuleApi.listHitlRules).toHaveBeenCalledWith('team-1')
  })

  it('useCreateHitlRule creates rule and invalidates query', async () => {
    const newRule: HitlRuleDto = {
      action: 'DENY',
      commandRoot: 'rm -rf',
      createdAt: '2026-09-06T12:00:00Z',
      createdByName: 'Alice',
      createdByUserId: 'user-1',
      id: 'rule-2',
      ruleType: 'PREFIX_WILD',
      teamId: 'team-1',
    }
    vi.mocked(hitlRuleApi.createHitlRule).mockResolvedValue(newRule)

    const { result } = renderHook(() => useCreateHitlRule(), {
      wrapper: createWrapper(),
    })

    await act(async () => {
      await result.current.mutateAsync({
        action: 'DENY',
        commandRoot: 'rm -rf',
        ruleType: 'PREFIX_WILD',
      })
    })

    expect(hitlRuleApi.createHitlRule).toHaveBeenCalledWith('team-1', {
      action: 'DENY',
      commandRoot: 'rm -rf',
      ruleType: 'PREFIX_WILD',
    })
  })

  it('useDeleteHitlRule deletes rule and invalidates query', async () => {
    vi.mocked(hitlRuleApi.deleteHitlRule).mockResolvedValue(undefined)

    const { result } = renderHook(() => useDeleteHitlRule(), {
      wrapper: createWrapper(),
    })

    await act(async () => {
      await result.current.mutateAsync('rule-1')
    })

    expect(hitlRuleApi.deleteHitlRule).toHaveBeenCalledWith('team-1', 'rule-1')
  })
})

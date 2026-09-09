import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { SandboxPermissionRuleDto } from '@/types/permission-types'

import {
  useCreatePermissionRule,
  useDeletePermissionRule,
  usePermissionRules,
} from '@/hooks/use-permissions'
import * as permissionApi from '@/lib/permission-api'
import { useAuthStore } from '@/store/auth-store'

vi.mock('@/lib/permission-api', () => ({
  createPermissionRule: vi.fn(),
  deletePermissionRule: vi.fn(),
  listPermissionRules: vi.fn(),
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

const mockRules: SandboxPermissionRuleDto[] = [
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

describe('use-permissions hooks', () => {
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

  it('usePermissionRules fetches rules for current team', async () => {
    vi.mocked(permissionApi.listPermissionRules).mockResolvedValue(mockRules)

    const { result } = renderHook(() => usePermissionRules(), {
      wrapper: createWrapper(),
    })

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true)
    })

    expect(result.current.data).toEqual(mockRules)
    expect(permissionApi.listPermissionRules).toHaveBeenCalledWith('team-1')
  })

  it('useCreatePermissionRule creates rule and invalidates query', async () => {
    const newRule: SandboxPermissionRuleDto = {
      action: 'DENY',
      commandRoot: 'rm -rf',
      createdAt: '2026-09-06T12:00:00Z',
      createdByName: 'Alice',
      createdByUserId: 'user-1',
      id: 'rule-2',
      ruleType: 'PREFIX_WILD',
      teamId: 'team-1',
    }
    vi.mocked(permissionApi.createPermissionRule).mockResolvedValue(newRule)

    const { result } = renderHook(() => useCreatePermissionRule(), {
      wrapper: createWrapper(),
    })

    await act(async () => {
      await result.current.mutateAsync({
        action: 'DENY',
        commandRoot: 'rm -rf',
        ruleType: 'PREFIX_WILD',
      })
    })

    expect(permissionApi.createPermissionRule).toHaveBeenCalledWith('team-1', {
      action: 'DENY',
      commandRoot: 'rm -rf',
      ruleType: 'PREFIX_WILD',
    })
  })

  it('useDeletePermissionRule deletes rule and invalidates query', async () => {
    vi.mocked(permissionApi.deletePermissionRule).mockResolvedValue(undefined)

    const { result } = renderHook(() => useDeletePermissionRule(), {
      wrapper: createWrapper(),
    })

    await act(async () => {
      await result.current.mutateAsync('rule-1')
    })

    expect(permissionApi.deletePermissionRule).toHaveBeenCalledWith('team-1', 'rule-1')
  })
})

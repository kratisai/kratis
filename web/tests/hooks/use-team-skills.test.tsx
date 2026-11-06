import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { TeamSkillDto } from '@/types/team-skill-types'

import {
  useDeleteTeamSkill,
  useTeamSkills,
  useUpdateTeamSkill,
  useUploadTeamSkill,
} from '@/hooks/use-team-skills'
import * as teamSkillApi from '@/lib/team-skill-api'
import { useAuthStore } from '@/store/auth-store'

vi.mock('@/lib/team-skill-api', () => ({
  deleteTeamSkill: vi.fn(),
  listTeamSkills: vi.fn(),
  updateTeamSkill: vi.fn(),
  uploadTeamSkill: vi.fn(),
}))

const mockSkill: TeamSkillDto = {
  createdAt: '2026-09-06T12:00:00Z',
  description: 'Run lint',
  enabled: true,
  id: 'skill-1',
  name: 'lint',
  updatedAt: '2026-09-06T12:00:00Z',
}

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

describe('use-team-skills hooks', () => {
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

  it('loads skills for the current team', async () => {
    vi.mocked(teamSkillApi.listTeamSkills).mockResolvedValue([mockSkill])

    const { result } = renderHook(() => useTeamSkills(), { wrapper: createWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual([mockSkill])
    expect(teamSkillApi.listTeamSkills).toHaveBeenCalledWith('team-1')
  })

  it('uploads a skill', async () => {
    vi.mocked(teamSkillApi.uploadTeamSkill).mockResolvedValue(mockSkill)
    const file = new File(['archive'], 'lint.tar.gz')

    const { result } = renderHook(() => useUploadTeamSkill(), { wrapper: createWrapper() })
    await act(async () => {
      await result.current.mutateAsync(file)
    })

    expect(teamSkillApi.uploadTeamSkill).toHaveBeenCalledWith('team-1', file)
  })

  it('updates a skill without leaking skillId into the request body', async () => {
    vi.mocked(teamSkillApi.updateTeamSkill).mockResolvedValue(mockSkill)

    const { result } = renderHook(() => useUpdateTeamSkill(), { wrapper: createWrapper() })
    await act(async () => {
      await result.current.mutateAsync({ enabled: false, skillId: 'skill-1' })
    })

    expect(teamSkillApi.updateTeamSkill).toHaveBeenCalledWith('team-1', 'skill-1', {
      enabled: false,
    })
  })

  it('deletes a skill', async () => {
    vi.mocked(teamSkillApi.deleteTeamSkill).mockResolvedValue(undefined)

    const { result } = renderHook(() => useDeleteTeamSkill(), { wrapper: createWrapper() })
    await act(async () => {
      await result.current.mutateAsync('skill-1')
    })

    expect(teamSkillApi.deleteTeamSkill).toHaveBeenCalledWith('team-1', 'skill-1')
  })
})
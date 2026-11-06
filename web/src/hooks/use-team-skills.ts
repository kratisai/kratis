import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import type { UpdateTeamSkillRequest } from '@/types/team-skill-types'

import {
  deleteTeamSkill,
  listTeamSkills,
  updateTeamSkill,
  uploadTeamSkill,
} from '@/lib/team-skill-api'
import { useAuthStore } from '@/store/auth-store'

const TEAM_SKILLS_QUERY_KEY = 'team-skills'

interface UpdateTeamSkillVariables extends UpdateTeamSkillRequest {
  skillId: string
}

export function useDeleteTeamSkill() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (skillId: string) => {
      if (!teamId) throw new Error('No team selected')
      return deleteTeamSkill(teamId, skillId)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to delete skill')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [TEAM_SKILLS_QUERY_KEY, teamId] })
      toast.success('Skill deleted successfully')
    },
  })
}

export function useTeamSkills(teamId?: string) {
  const currentTeamId = useAuthStore().currentTeamId
  const effectiveTeamId = teamId || currentTeamId

  return useQuery({
    enabled: !!effectiveTeamId,
    queryFn: () => listTeamSkills(effectiveTeamId!),
    queryKey: [TEAM_SKILLS_QUERY_KEY, effectiveTeamId],
  })
}

export function useUpdateTeamSkill() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (variables: UpdateTeamSkillVariables) => {
      if (!teamId) throw new Error('No team selected')
      const { skillId, ...data } = variables
      return updateTeamSkill(teamId, skillId, data)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to update skill')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [TEAM_SKILLS_QUERY_KEY, teamId] })
      toast.success('Skill updated successfully')
    },
  })
}

export function useUploadTeamSkill() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (file: File) => {
      if (!teamId) throw new Error('No team selected')
      return uploadTeamSkill(teamId, file)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to upload skill')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [TEAM_SKILLS_QUERY_KEY, teamId] })
      toast.success('Skill uploaded successfully')
    },
  })
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import type { CreateHitlRuleRequest } from '@/types/hitl-rule-types'

import { createHitlRule, deleteHitlRule, listHitlRules } from '@/lib/hitl-rule-api'
import { useAuthStore } from '@/store/auth-store'

export const HITL_RULES_QUERY_KEY = 'permissions'

export function useCreateHitlRule() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (data: CreateHitlRuleRequest) => {
      if (!teamId) throw new Error('No team selected')
      return createHitlRule(teamId, data)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to create HITL rule')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [HITL_RULES_QUERY_KEY, teamId],
      })
      toast.success('HITL rule created successfully')
    },
  })
}

export function useDeleteHitlRule() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (ruleId: string) => {
      if (!teamId) throw new Error('No team selected')
      return deleteHitlRule(teamId, ruleId)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to delete HITL rule')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [HITL_RULES_QUERY_KEY, teamId],
      })
      toast.success('HITL rule deleted successfully')
    },
  })
}

export function useHitlRules(teamId?: string) {
  const currentTeamId = useAuthStore().currentTeamId
  const effectiveTeamId = teamId || currentTeamId

  return useQuery({
    enabled: !!effectiveTeamId,
    queryFn: () => listHitlRules(effectiveTeamId!),
    queryKey: [HITL_RULES_QUERY_KEY, effectiveTeamId],
  })
}

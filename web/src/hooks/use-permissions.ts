import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import type { CreateSandboxPermissionRuleRequest } from '@/types/permission-types'

import {
  createPermissionRule,
  deletePermissionRule,
  listPermissionRules,
} from '@/lib/permission-api'
import { useAuthStore } from '@/store/auth-store'

export const PERMISSIONS_QUERY_KEY = 'permissions'

export function useCreatePermissionRule() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (data: CreateSandboxPermissionRuleRequest) => {
      if (!teamId) throw new Error('No team selected')
      return createPermissionRule(teamId, data)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to create permission rule')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [PERMISSIONS_QUERY_KEY, teamId],
      })
      toast.success('Permission rule created successfully')
    },
  })
}

export function useDeletePermissionRule() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (ruleId: string) => {
      if (!teamId) throw new Error('No team selected')
      return deletePermissionRule(teamId, ruleId)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to delete permission rule')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [PERMISSIONS_QUERY_KEY, teamId],
      })
      toast.success('Permission rule deleted successfully')
    },
  })
}

export function usePermissionRules(teamId?: string) {
  const currentTeamId = useAuthStore().currentTeamId
  const effectiveTeamId = teamId || currentTeamId

  return useQuery({
    enabled: !!effectiveTeamId,
    queryFn: () => listPermissionRules(effectiveTeamId!),
    queryKey: [PERMISSIONS_QUERY_KEY, effectiveTeamId],
  })
}

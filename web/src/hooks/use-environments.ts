import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import type {
  CreateEnvironmentRequest,
  CreateEnvironmentResponse,
  ExecutionEnvironmentDto,
} from '@/lib/environment-api'

import {
  createConnector,
  deleteEnvironment,
  getEnvironments,
  terminateEnvironment,
} from '@/lib/environment-api'
import { useAuthStore } from '@/store/auth-store'

const ENVIRONMENTS_QUERY_KEY = 'environments'

export function useCreateConnector() {
  const queryClient = useQueryClient()

  return useMutation<CreateEnvironmentResponse, Error, CreateEnvironmentRequest>({
    mutationFn: (data) => {
      const teamId = useAuthStore.getState().currentTeamId
      if (!teamId) throw new Error('No team selected')
      return createConnector(teamId, data)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to create connector')
    },
    onSuccess: () => {
      const teamId = useAuthStore.getState().currentTeamId
      void queryClient.invalidateQueries({
        queryKey: [ENVIRONMENTS_QUERY_KEY, teamId],
      })
      toast.success('Connector created successfully')
    },
  })
}

export function useDeleteEnvironment() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (envId: string) => {
      const teamId = useAuthStore.getState().currentTeamId
      if (!teamId) throw new Error('No team selected')
      return deleteEnvironment(teamId, envId)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to delete environment')
    },
    onSuccess: () => {
      const teamId = useAuthStore.getState().currentTeamId
      void queryClient.invalidateQueries({
        queryKey: [ENVIRONMENTS_QUERY_KEY, teamId],
      })
      toast.success('Environment deleted successfully')
    },
  })
}

export function useEnvironments() {
  const teamId = useAuthStore().currentTeamId
  return useQuery<ExecutionEnvironmentDto[]>({
    enabled: !!teamId,
    queryFn: () => getEnvironments(teamId!),
    queryKey: [ENVIRONMENTS_QUERY_KEY, teamId],
  })
}

export function useTerminateEnvironment() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (envId: string) => {
      const teamId = useAuthStore.getState().currentTeamId
      if (!teamId) throw new Error('No team selected')
      return terminateEnvironment(teamId, envId)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to terminate environment')
    },
    onSuccess: () => {
      const teamId = useAuthStore.getState().currentTeamId
      void queryClient.invalidateQueries({
        queryKey: [ENVIRONMENTS_QUERY_KEY, teamId],
      })
      toast.success('Environment terminated successfully')
    },
  })
}

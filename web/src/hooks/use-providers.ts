import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import type {
  CreateProviderData,
  EnvironmentProviderDto,
  UpdateProviderData,
} from '@/lib/provider-api'

import { createProvider, deleteProvider, getProviders, updateProvider } from '@/lib/provider-api'
import { useAuthStore } from '@/store/auth-store'

const PROVIDERS_QUERY_KEY = 'providers'

export function useCreateProvider() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (data: CreateProviderData) => {
      const teamId = useAuthStore.getState().currentTeamId
      if (!teamId) throw new Error('No team selected')
      return createProvider(teamId, data)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to create provider')
    },
    onSuccess: () => {
      const teamId = useAuthStore.getState().currentTeamId
      void queryClient.invalidateQueries({
        queryKey: [PROVIDERS_QUERY_KEY, teamId],
      })
      toast.success('Provider created successfully')
    },
  })
}

export function useDeleteProvider() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (providerId: string) => {
      const teamId = useAuthStore.getState().currentTeamId
      if (!teamId) throw new Error('No team selected')
      return deleteProvider(teamId, providerId)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to delete provider')
    },
    onSuccess: () => {
      const teamId = useAuthStore.getState().currentTeamId
      void queryClient.invalidateQueries({
        queryKey: [PROVIDERS_QUERY_KEY, teamId],
      })
      toast.success('Provider deleted successfully')
    },
  })
}

export function useProviders() {
  const teamId = useAuthStore().currentTeamId
  return useQuery<EnvironmentProviderDto[]>({
    enabled: !!teamId,
    queryFn: () => getProviders(teamId!),
    queryKey: [PROVIDERS_QUERY_KEY, teamId],
  })
}

export function useUpdateProvider() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (variables: { data: UpdateProviderData; providerId: string }) => {
      const teamId = useAuthStore.getState().currentTeamId
      if (!teamId) throw new Error('No team selected')
      return updateProvider(teamId, variables.providerId, variables.data)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to update provider')
    },
    onSuccess: () => {
      const teamId = useAuthStore.getState().currentTeamId
      void queryClient.invalidateQueries({
        queryKey: [PROVIDERS_QUERY_KEY, teamId],
      })
      toast.success('Provider updated successfully')
    },
  })
}

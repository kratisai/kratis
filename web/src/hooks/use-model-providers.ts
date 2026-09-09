import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import type { CreateModelProviderRequest, UpdateModelProviderRequest } from '@/types/auth-types'

import {
  createModelProvider,
  deleteModelProvider,
  discoverModels,
  getSupportedTypes,
  listModelProviders,
  testConnection,
  updateModelProvider,
} from '@/lib/model-provider-api'
import { useAuthStore } from '@/store/auth-store'

const MODEL_PROVIDERS_QUERY_KEY = 'model-providers'
const SUPPORTED_TYPES_QUERY_KEY = 'supported-provider-types'

export function useCreateModelProvider() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (data: CreateModelProviderRequest) => {
      if (!teamId) throw new Error('No team selected')
      return createModelProvider(teamId, data)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to add model provider')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [MODEL_PROVIDERS_QUERY_KEY, teamId],
      })
      toast.success('Model provider added successfully')
    },
  })
}

export function useDeleteModelProvider() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (providerId: string) => {
      if (!teamId) throw new Error('No team selected')
      return deleteModelProvider(teamId, providerId)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to delete model provider')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [MODEL_PROVIDERS_QUERY_KEY, teamId],
      })
      toast.success('Model provider deleted successfully')
    },
  })
}

export function useDiscoverModels(providerId: null | string) {
  return useQuery({
    enabled: !!providerId,
    queryFn: () => discoverModels(providerId!),
    queryKey: ['discover-models', providerId],
    staleTime: 1000 * 60 * 5, // Cache for 5 minutes
  })
}

export function useModelProviders() {
  const teamId = useAuthStore().currentTeamId
  return useQuery({
    enabled: !!teamId,
    queryFn: () => listModelProviders(teamId!),
    queryKey: [MODEL_PROVIDERS_QUERY_KEY, teamId],
  })
}

export function useSupportedProviderTypes() {
  return useQuery({
    queryFn: getSupportedTypes,
    queryKey: [SUPPORTED_TYPES_QUERY_KEY],
    staleTime: 1000 * 60 * 60, // Cache for 1 hour
  })
}

export function useTestConnection() {
  return useMutation({
    mutationFn: testConnection,
  })
}

export function useUpdateModelProvider() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: ({
      data,
      providerId,
    }: {
      data: UpdateModelProviderRequest
      providerId: string
    }) => {
      if (!teamId) throw new Error('No team selected')
      return updateModelProvider(teamId, providerId, data)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to update model provider')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [MODEL_PROVIDERS_QUERY_KEY, teamId],
      })
      toast.success('Model provider updated successfully')
    },
  })
}

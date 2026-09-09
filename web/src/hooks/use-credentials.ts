import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import type { SaveRepoCredentialRequest } from '@/types/auth-types'

import {
  createCredential,
  deleteCredential,
  generateSshKey,
  getAffectedRepositories,
  listAvailableRepos,
  listCredentials,
  updateCredential,
  validateGitHubAppInstallation,
} from '@/lib/credential-api'
import { useAuthStore } from '@/store/auth-store'

const CREDENTIALS_QUERY_KEY = 'credentials'
const AVAILABLE_REPOS_QUERY_KEY = 'availableRepos'
const AFFECTED_REPOS_QUERY_KEY = 'affectedRepositories'

interface UpdateCredentialVariables {
  credentialId: string
  data: SaveRepoCredentialRequest
}

export function useAffectedRepositories(credentialId: string | undefined) {
  const teamId = useAuthStore().currentTeamId

  return useQuery({
    enabled: !!teamId && !!credentialId,
    queryFn: () => getAffectedRepositories(teamId!, credentialId!),
    queryKey: [AFFECTED_REPOS_QUERY_KEY, teamId, credentialId],
    retry: 1,
    staleTime: 0, // Always refetch when opened
  })
}

export function useAvailableRepos(credentialId: null | string | undefined, enabled = true) {
  const teamId = useAuthStore().currentTeamId

  return useQuery({
    enabled: !!teamId && !!credentialId && enabled,
    queryFn: () => listAvailableRepos(teamId!, credentialId!),
    queryKey: [AVAILABLE_REPOS_QUERY_KEY, teamId, credentialId],
    retry: 1,
    staleTime: 30000, // 30 seconds cache
  })
}

export function useCreateCredential() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (data: SaveRepoCredentialRequest) => {
      if (!teamId) throw new Error('No team selected')
      return createCredential(teamId, data)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to create credential')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [CREDENTIALS_QUERY_KEY, teamId],
      })
      toast.success('Credential saved successfully')
    },
  })
}

export function useCredentials() {
  const teamId = useAuthStore().currentTeamId
  return useQuery({
    enabled: !!teamId,
    queryFn: () => listCredentials(teamId!),
    queryKey: [CREDENTIALS_QUERY_KEY, teamId],
  })
}

export function useDeleteCredential() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (credentialId: string) => {
      if (!teamId) throw new Error('No team selected')
      return deleteCredential(teamId, credentialId)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to delete credential')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [CREDENTIALS_QUERY_KEY, teamId],
      })
      toast.success('Credential deleted successfully')
    },
  })
}

export function useGenerateSshKey() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (name: string) => {
      if (!teamId) throw new Error('No team selected')
      return generateSshKey(teamId, name)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to generate SSH key')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [CREDENTIALS_QUERY_KEY, teamId],
      })
      toast.success('SSH Key generated successfully')
    },
  })
}

export function useUpdateCredential() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: ({ credentialId, data }: UpdateCredentialVariables) => {
      if (!teamId) throw new Error('No team selected')
      return updateCredential(teamId, credentialId, data)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to update credential')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [CREDENTIALS_QUERY_KEY, teamId],
      })
      toast.success('Credential updated successfully')
    },
  })
}

export function useValidateGitHubAppInstallation() {
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (installationId: string) => {
      if (!teamId) throw new Error('No team selected')
      return validateGitHubAppInstallation(teamId, installationId)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to validate GitHub App installation')
    },
  })
}

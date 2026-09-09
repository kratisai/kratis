import {
  type QueryClient,
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query'
import { toast } from 'sonner'

import type {
  IngestionBatchLogDto,
  IngestionStatusDto,
  RegisterRepositoryRequest,
  UpdateRepositoryRequest,
} from '@/lib/repo-api'
import type { IngestionBatchStatsDto } from '@/types/auth-types'

import {
  createRepository,
  deleteRepository,
  getBatchHistory,
  getBatchLogs,
  getBatchStats,
  getIngestionStatus,
  listRepositories,
  triggerIngestion,
  updateRepository,
} from '@/lib/repo-api'
import { useAuthStore } from '@/store/auth-store'

const REPOSITORIES_QUERY_KEY = 'repositories'

interface CreateRepositoryVariables extends RegisterRepositoryRequest {
  ingestImmediately?: boolean
}

interface UpdateRepositoryVariables extends UpdateRepositoryRequest {
  repoId: string
}

export function useBatchHistory(repoId: string): UseQueryResult<IngestionStatusDto[], Error> {
  const teamId = useAuthStore().currentTeamId

  return useQuery<IngestionStatusDto[]>({
    enabled: !!teamId && !!repoId,
    queryFn: () => getBatchHistory(teamId!, repoId),
    queryKey: ['batch-history', teamId, repoId],
  })
}

export function useBatchLogs(
  repoId: string,
  batchId: null | string,
): UseQueryResult<IngestionBatchLogDto[], Error> {
  const teamId = useAuthStore().currentTeamId

  return useQuery<IngestionBatchLogDto[]>({
    enabled: !!teamId && !!repoId && !!batchId,
    // Return empty array when query is disabled (batchId is null)
    placeholderData: [],
    queryFn: () => getBatchLogs(teamId!, repoId, batchId!),
    queryKey: ['batch-logs', teamId, repoId, batchId],
    refetchInterval: (query) => {
      const logs = query.state.data
      if (!logs || logs.length === 0) return 2000
      const isRunning = logs.some((log) => log.step !== 'SUCCESS' && log.step !== 'FAILED')
      return isRunning ? 2000 : false
    },
  })
}

export function useBatchStats(
  repoId: string,
  batchId: null | string,
): UseQueryResult<IngestionBatchStatsDto | null, Error> {
  const teamId = useAuthStore().currentTeamId

  return useQuery<IngestionBatchStatsDto | null>({
    enabled: !!teamId && !!repoId && !!batchId,
    placeholderData: null,
    queryFn: () => getBatchStats(teamId!, repoId, batchId!),
    queryKey: ['batch-stats', teamId, repoId, batchId],
  })
}

export function useCreateRepository() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (variables: CreateRepositoryVariables) => {
      const teamId = useAuthStore.getState().currentTeamId
      if (!teamId) throw new Error('No team selected')
      return createRepository(teamId, {
        branch: variables.branch,
        credentialId: variables.credentialId,
        name: variables.name,
        repositoryType: variables.repositoryType,
        url: variables.url,
      })
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to add repository')
    },
    onSuccess: (created, variables) => {
      const teamId = useAuthStore.getState().currentTeamId
      void queryClient.invalidateQueries({
        queryKey: [REPOSITORIES_QUERY_KEY, teamId],
      })
      toast.success('Repository added successfully')
      if (variables.ingestImmediately && teamId) {
        // useCreateRepository mutation is called once per repo, trigger ingestion once per repo.
        void triggerIngestion(teamId, created.id)
          .then(() => invalidateRepositoryIngestionQueries(queryClient, created.id))
          .catch((error: Error) => {
            toast.error(error.message || 'Failed to start ingestion')
          })
      }
    },
  })
}

export function useDeleteRepository() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (repoId: string) => {
      if (!teamId) throw new Error('No team selected')
      return deleteRepository(teamId, repoId)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to delete repository')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [REPOSITORIES_QUERY_KEY, teamId],
      })
      toast.success('Repository deleted successfully')
    },
  })
}

export function useIngestionStatus(repoId: string, enabled = true) {
  const teamId = useAuthStore().currentTeamId

  return useQuery({
    enabled: !!teamId && !!repoId && enabled,
    queryFn: () => getIngestionStatus(teamId!, repoId),
    queryKey: ['ingestion-status', teamId, repoId],
    refetchInterval: (query) => {
      const data = query.state.data
      const isRunning = data?.status === 'QUEUED' || data?.status === 'PROCESSING'
      return isRunning ? 2000 : false
    },
  })
}

export function useRepositories() {
  const teamId = useAuthStore().currentTeamId
  return useQuery({
    enabled: !!teamId,
    queryFn: () => listRepositories(teamId!),
    queryKey: [REPOSITORIES_QUERY_KEY, teamId],
  })
}

export function useTriggerIngestion() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (repoId: string) => {
      if (!teamId) throw new Error('No team selected')
      return triggerIngestion(teamId, repoId)
    },
    onError: (error: Error, repoId) => {
      invalidateRepositoryIngestionQueries(queryClient, repoId)
      toast.error(error.message || 'Failed to start ingestion')
    },
    onSuccess: (_data, repoId) => {
      invalidateRepositoryIngestionQueries(queryClient, repoId)
      toast.success('Ingestion started')
    },
  })
}

export function useUpdateRepository() {
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  return useMutation({
    mutationFn: (variables: UpdateRepositoryVariables) => {
      if (!teamId) throw new Error('No team selected')
      return updateRepository(teamId, variables.repoId, variables)
    },
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to update repository')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [REPOSITORIES_QUERY_KEY, teamId],
      })
      toast.success('Repository updated successfully')
    },
  })
}

function invalidateRepositoryIngestionQueries(queryClient: QueryClient, repoId: string) {
  const teamId = useAuthStore.getState().currentTeamId
  if (!teamId) return
  // Invalidate ingestion status so batchId updates to the new batch
  void queryClient.invalidateQueries({
    queryKey: ['ingestion-status', teamId, repoId],
  })
  // Invalidate batch history to show the new ingestion
  void queryClient.invalidateQueries({
    queryKey: ['batch-history', teamId, repoId],
  })
  void queryClient.invalidateQueries({
    queryKey: [REPOSITORIES_QUERY_KEY, teamId],
  })
}

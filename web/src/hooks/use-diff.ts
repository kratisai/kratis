import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import type { SteerExecutionRequest } from '@/types/diff-types'

import { fetchDiffSummary, fetchFileDiff, steerExecution } from '@/lib/diff-api'

export const DIFF_QUERY_KEYS = {
  /** Prefix matching every cached diff query, for change-driven invalidation. */
  all: ['execution-diff'] as const,
  /** Prefix matching every diff query of one execution. */
  execution: (chatId: string, executionId: string) => ['execution-diff', chatId, executionId],
  file: (chatId: string, executionId: string, path: string) => [
    ...DIFF_QUERY_KEYS.execution(chatId, executionId),
    'file',
    path,
  ],
  summary: (chatId: string, executionId: string) => [
    ...DIFF_QUERY_KEYS.execution(chatId, executionId),
    'summary',
  ],
}

export function useDiffSummary(
  chatId: null | string | undefined,
  executionId: null | string | undefined,
) {
  return useQuery({
    enabled: Boolean(chatId && executionId),
    queryFn: () => fetchDiffSummary(chatId!, executionId!),
    queryKey: DIFF_QUERY_KEYS.summary(chatId || '', executionId || ''),
    staleTime: 3000,
  })
}

export function useFileDiff(
  chatId: null | string | undefined,
  executionId: null | string | undefined,
  path: string,
  enabled: boolean = true,
) {
  return useQuery({
    enabled: Boolean(chatId && executionId && path && enabled),
    queryFn: () => fetchFileDiff(chatId!, executionId!, path),
    queryKey: DIFF_QUERY_KEYS.file(chatId || '', executionId || '', path),
    staleTime: 5000,
  })
}

export function useSteerExecution(chatId: string, executionId: string) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (request: SteerExecutionRequest) => steerExecution(chatId, executionId, request),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: DIFF_QUERY_KEYS.summary(chatId, executionId),
      })
    },
  })
}

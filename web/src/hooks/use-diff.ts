import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import type { SteerExecutionRequest } from '@/types/diff-types'

import { fetchDiffSummary, fetchFileDiff, fetchFileSlice, steerExecution } from '@/lib/diff-api'

export const DIFF_QUERY_KEYS = {
  file: (chatId: string, executionId: string, path: string) => [
    'execution-diff-file',
    chatId,
    executionId,
    path,
  ],
  slice: (chatId: string, executionId: string, path: string, start: number, end: number) => [
    'execution-diff-slice',
    chatId,
    executionId,
    path,
    start,
    end,
  ],
  summary: (chatId: string, executionId: string) => ['execution-diff-summary', chatId, executionId],
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

export function useFileSlice(
  chatId: null | string | undefined,
  executionId: null | string | undefined,
  path: string,
  startLine: number,
  endLine: number,
  enabled: boolean = true,
) {
  return useQuery({
    enabled: Boolean(chatId && executionId && path && enabled && startLine <= endLine),
    queryFn: () => fetchFileSlice(chatId!, executionId!, path, startLine, endLine),
    queryKey: DIFF_QUERY_KEYS.slice(chatId || '', executionId || '', path, startLine, endLine),
    staleTime: Infinity,
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

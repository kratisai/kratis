import type {
  DiffFileDto,
  DiffSummaryDto,
  ReadFileSliceDto,
  SteerExecutionRequest,
} from '@/types/diff-types'

import { useAuthStore } from '@/store/auth-store'

import { ApiError } from './auth-api'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export async function fetchDiffSummary(
  chatId: string,
  executionId: string,
): Promise<DiffSummaryDto> {
  const response = await fetchWithAuth(
    `/api/v1/chats/${chatId}/executions/${executionId}/diff/summary`,
  )
  return response.json()
}

export async function fetchFileDiff(
  chatId: string,
  executionId: string,
  path: string,
): Promise<DiffFileDto> {
  const params = new URLSearchParams({ path })
  const response = await fetchWithAuth(
    `/api/v1/chats/${chatId}/executions/${executionId}/diff/file?${params.toString()}`,
  )
  return response.json()
}

export async function fetchFileSlice(
  chatId: string,
  executionId: string,
  path: string,
  startLine: number,
  endLine: number,
): Promise<ReadFileSliceDto> {
  const params = new URLSearchParams({
    endLine: endLine.toString(),
    path,
    startLine: startLine.toString(),
  })
  const response = await fetchWithAuth(
    `/api/v1/chats/${chatId}/executions/${executionId}/diff/context?${params.toString()}`,
  )
  return response.json()
}

export async function steerExecution(
  chatId: string,
  executionId: string,
  request: SteerExecutionRequest,
): Promise<void> {
  await fetchWithAuth(`/api/v1/chats/${chatId}/executions/${executionId}/steer`, {
    body: JSON.stringify(request),
    method: 'POST',
  })
}

async function fetchWithAuth(url: string, options: RequestInit = {}): Promise<Response> {
  const { accessToken } = useAuthStore.getState()

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string>),
  }

  if (accessToken) {
    headers['Authorization'] = `Bearer ${accessToken}`
  }

  const response = await window.fetch(`${API_BASE_URL}${url}`, {
    ...options,
    headers,
  })

  if (!response.ok) {
    const errorData = await response.json().catch(() => null)
    throw new ApiError(errorData?.message || `HTTP ${response.status}`, response.status, errorData)
  }

  return response
}

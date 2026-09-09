import type { IngestionBatchStatsDto, RepositoryDto } from '@/types/auth-types'

import { useAuthStore } from '@/store/auth-store'

import { ApiError } from './auth-api'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export interface IngestionBatchLogDto {
  batchId: string
  createdAt: string
  id: string
  level: string
  message: string
  step: string
}

export interface IngestionStatusDto {
  batchId: string
  commitHash?: null | string
  completedAt?: null | string
  errorMessage?: null | string
  lastIngestedAt?: null | string
  queuePosition?: null | number
  startedAt?: null | string
  status: string
}

export interface IngestionTriggerResponse {
  batchId: string
  status: string
}

export interface RegisterRepositoryRequest {
  branch?: string
  credentialId?: string
  name: string
  repositoryType: string
  url: string
}

export interface UpdateRepositoryRequest {
  branch?: string
  credentialId?: string
  name?: string
  repositoryType: string
}

export async function createRepository(
  teamId: string,
  data: RegisterRepositoryRequest,
): Promise<RepositoryDto> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/repositories`, {
    body: JSON.stringify(data),
    method: 'POST',
  })
  return response.json()
}

export async function deleteRepository(teamId: string, repoId: string): Promise<void> {
  await fetchWithAuth(`/api/v1/teams/${teamId}/repositories/${repoId}`, {
    method: 'DELETE',
  })
}

export async function getBatchHistory(
  teamId: string,
  repoId: string,
): Promise<IngestionStatusDto[]> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/repositories/${repoId}/batches`)
  return response.json()
}

export async function getBatchLogs(
  teamId: string,
  repoId: string,
  batchId: string,
): Promise<IngestionBatchLogDto[]> {
  const response = await fetchWithAuth(
    `/api/v1/teams/${teamId}/repositories/${repoId}/batches/${batchId}/logs`,
  )
  return response.json()
}

export async function getBatchStats(
  teamId: string,
  repoId: string,
  batchId: string,
): Promise<IngestionBatchStatsDto> {
  const response = await fetchWithAuth(
    `/api/v1/teams/${teamId}/repositories/${repoId}/batches/${batchId}/stats`,
  )
  return response.json()
}

export async function getIngestionStatus(
  teamId: string,
  repoId: string,
): Promise<IngestionStatusDto | null> {
  const response = await fetchWithAuth(
    `/api/v1/teams/${teamId}/repositories/${repoId}/ingestion-status`,
  )
  if (response.status === 204) return null
  return response.json().catch(() => null)
}

export async function listRepositories(teamId: string): Promise<RepositoryDto[]> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/repositories`)
  return response.json()
}

export async function triggerIngestion(
  teamId: string,
  repoId: string,
): Promise<IngestionTriggerResponse> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/repositories/${repoId}/ingest`, {
    method: 'POST',
  })
  return response.json()
}

export async function updateRepository(
  teamId: string,
  repoId: string,
  data: UpdateRepositoryRequest,
): Promise<RepositoryDto> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/repositories/${repoId}`, {
    body: JSON.stringify(data),
    method: 'PUT',
  })
  return response.json()
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

import { useAuthStore } from '@/store/auth-store'

import { ApiError } from './auth-api'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export interface TeamWikiBatchDto {
  buildMode: TeamWikiBuildMode
  completedAt: null | string
  errorMessage: null | string
  id: string
  isActive: boolean
  repoCount: number
  startedAt: string
  status: TeamWikiBuildStatus
  totalToolCalls: null | number
  triggerKind: TeamWikiTriggerKind
}

export interface TeamWikiBatchLogDto {
  createdAt: string
  id: string
  level: string
  message: string
  step: string
}

export type TeamWikiBuildMode = 'FULL' | 'INCREMENTAL'
export interface TeamWikiStatusDto {
  activeBatch: null | TeamWikiBatchDto
  deferralReason: TeamWikiDeferralReason
  dirty: boolean
  ingestedRepoCount: number
  latestBatch: null | TeamWikiBatchDto
  nextEligibleAt: null | string
  nonStructuralChangedRepoCount: number
  recentBatches: TeamWikiBatchDto[]
}
export interface WikiPageDto {
  content: string
  hasChildren: boolean
  id: string
  orderIndex: number
  pageSlug: string
  parentPageId: null | string
  repoName: null | string
  title: string
}

type TeamWikiBuildStatus = 'FAILED' | 'PROCESSING' | 'QUEUED' | 'SUCCESS'

type TeamWikiDeferralReason = 'FAILURE_BACKOFF' | 'IN_FLIGHT_REPOS' | 'NONE' | 'QUOTA_REACHED'

type TeamWikiTriggerKind = 'AUTOMATIC' | 'MANUAL'

export async function getTeamWikiBatchLogs(
  teamId: string,
  batchId: string,
): Promise<TeamWikiBatchLogDto[]> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/wiki/batches/${batchId}/logs`)
  return response.json()
}

export async function getTeamWikiChildPages(
  teamId: string,
  pageId: string,
): Promise<WikiPageDto[]> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/wiki/pages/${pageId}/children`)
  return response.json()
}

export async function getTeamWikiPage(teamId: string, pageId: string): Promise<WikiPageDto> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/wiki/pages/${pageId}`)
  return response.json()
}

export async function getTeamWikiStatus(teamId: string): Promise<TeamWikiStatusDto> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/wiki/status`)
  return response.json()
}

export async function getTeamWikiTopLevelPages(teamId: string): Promise<WikiPageDto[]> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/wiki/pages`)
  return response.json()
}

export async function getWikiChildPages(
  teamId: string,
  repoId: string,
  pageId: string,
): Promise<WikiPageDto[]> {
  const response = await fetchWithAuth(
    `/api/v1/teams/${teamId}/repositories/${repoId}/wiki/pages/${pageId}/children`,
  )
  return response.json()
}

export async function getWikiPage(
  teamId: string,
  repoId: string,
  pageId: string,
): Promise<WikiPageDto> {
  const response = await fetchWithAuth(
    `/api/v1/teams/${teamId}/repositories/${repoId}/wiki/pages/${pageId}`,
  )
  return response.json()
}

export async function getWikiTopLevelPages(teamId: string, repoId: string): Promise<WikiPageDto[]> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/repositories/${repoId}/wiki/pages`)
  return response.json()
}

export async function triggerTeamWikiRebuild(
  teamId: string,
  mode?: TeamWikiBuildMode,
): Promise<TeamWikiBatchDto> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/wiki/rebuild`, {
    body: JSON.stringify(mode ? { mode } : {}),
    method: 'POST',
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

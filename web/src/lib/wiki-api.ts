import { useAuthStore } from '@/store/auth-store'

import { ApiError } from './auth-api'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export interface WikiPageDto {
  content: string
  hasChildren: boolean
  id: string
  orderIndex: number
  pageSlug: string
  parentPageId: null | string
  repoName: string
  title: string
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

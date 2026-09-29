import type {
  AddTeamMemberRequest,
  CreateTeamRequest,
  TeamDetailDto,
  TeamDto,
  TeamMemberDto,
  UpdateTeamRequest,
  UpdateUserRequest,
  UserDto,
} from '@/types/auth-types'

import { useAuthStore } from '@/store/auth-store'

import { ApiError } from './auth-api'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export async function addTeamMember(
  teamId: string,
  data: AddTeamMemberRequest,
): Promise<TeamMemberDto> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/members`, {
    body: JSON.stringify(data),
    method: 'POST',
  })
  return response.json()
}

export async function createTeam(data: CreateTeamRequest): Promise<TeamDto> {
  const response = await fetchWithAuth('/api/v1/teams', {
    body: JSON.stringify(data),
    method: 'POST',
  })
  return response.json()
}

export async function getTeam(teamId: string): Promise<TeamDetailDto> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}`)
  return response.json()
}

export async function listTeams(): Promise<TeamDto[]> {
  const response = await fetchWithAuth('/api/v1/teams')
  return response.json()
}

export async function removeTeamMember(teamId: string, userId: string): Promise<void> {
  await fetchWithAuth(`/api/v1/teams/${teamId}/members/${userId}`, {
    method: 'DELETE',
  })
}

export async function updateTavilyApiKey(
  teamId: string,
  tavilyApiKey: null | string,
): Promise<void> {
  await fetchWithAuth(`/api/v1/teams/${teamId}/tavily-api-key`, {
    body: JSON.stringify({ tavilyApiKey }),
    method: 'PATCH',
  })
}

export async function updateTeam(teamId: string, data: UpdateTeamRequest): Promise<TeamDto> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}`, {
    body: JSON.stringify(data),
    method: 'PUT',
  })
  return response.json()
}

export async function updateUserProfile(data: UpdateUserRequest): Promise<UserDto> {
  const response = await fetchWithAuth('/api/v1/auth/me', {
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

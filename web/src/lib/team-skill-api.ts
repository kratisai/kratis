import type { TeamSkillDto, UpdateTeamSkillRequest } from '@/types/team-skill-types'

import { useAuthStore } from '@/store/auth-store'

import { ApiError } from './auth-api'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export async function deleteTeamSkill(teamId: string, skillId: string): Promise<void> {
  await fetchWithAuth(`/api/v1/teams/${teamId}/skills/${skillId}`, {
    method: 'DELETE',
  })
}

export async function listTeamSkills(teamId: string): Promise<TeamSkillDto[]> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/skills`)
  return response.json()
}

export async function updateTeamSkill(
  teamId: string,
  skillId: string,
  data: UpdateTeamSkillRequest,
): Promise<TeamSkillDto> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/skills/${skillId}`, {
    body: JSON.stringify(data),
    method: 'PUT',
  })
  return response.json()
}

export async function uploadTeamSkill(teamId: string, file: File): Promise<TeamSkillDto> {
  const formData = new FormData()
  formData.append('file', file)
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/skills`, {
    body: formData,
    method: 'POST',
  })
  return response.json()
}

async function fetchWithAuth(url: string, options: RequestInit = {}): Promise<Response> {
  const { accessToken } = useAuthStore.getState()

  const headers: Record<string, string> = {
    ...(options.headers as Record<string, string>),
  }
  if (!(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
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

import type {
  CreateSandboxPermissionRuleRequest,
  SandboxPermissionRuleDto,
} from '@/types/permission-types'

import { useAuthStore } from '@/store/auth-store'

import { ApiError } from './auth-api'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export async function createPermissionRule(
  teamId: string,
  data: CreateSandboxPermissionRuleRequest,
): Promise<SandboxPermissionRuleDto> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/permissions`, {
    body: JSON.stringify(data),
    method: 'POST',
  })
  return response.json()
}

export async function deletePermissionRule(teamId: string, ruleId: string): Promise<void> {
  await fetchWithAuth(`/api/v1/teams/${teamId}/permissions/${ruleId}`, {
    method: 'DELETE',
  })
}

export async function listPermissionRules(teamId: string): Promise<SandboxPermissionRuleDto[]> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/permissions`)
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

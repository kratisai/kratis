import { useAuthStore } from '@/store/auth-store'

import { ApiError } from './auth-api'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export interface CreateEnvironmentRequest {
  name: string
}

export interface CreateEnvironmentResponse {
  environment: ExecutionEnvironmentDto
  installCommand: string
}

export interface ExecutionEnvironmentDto {
  containerId?: null | string
  createdAt: string
  id: string
  lastHeartbeat?: null | string
  name: string
  status: string
  teamId: string
  type: string
  updatedAt: string
}

export async function createConnector(
  teamId: string,
  data: CreateEnvironmentRequest,
): Promise<CreateEnvironmentResponse> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/environments`, {
    body: JSON.stringify(data),
    method: 'POST',
  })
  return response.json()
}

export async function deleteEnvironment(teamId: string, envId: string): Promise<void> {
  await fetchWithAuth(`/api/v1/teams/${teamId}/environments/${envId}`, {
    method: 'DELETE',
  })
}

export async function getEnvironments(teamId: string): Promise<ExecutionEnvironmentDto[]> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/environments`)
  return response.json()
}

export async function terminateEnvironment(teamId: string, envId: string): Promise<void> {
  await fetchWithAuth(`/api/v1/teams/${teamId}/environments/${envId}/terminate`, {
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

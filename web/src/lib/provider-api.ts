import { useAuthStore } from '@/store/auth-store'

import { ApiError } from './auth-api'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export interface CreateProviderData {
  dockerImage?: string
  name: string
}

export interface EnvironmentProviderDto {
  dockerImage?: null | string
  id: string
  name: string
  teamId: string
}

export interface UpdateProviderData {
  dockerImage?: string
  name?: string
}

export async function createProvider(
  teamId: string,
  data: CreateProviderData,
): Promise<EnvironmentProviderDto> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/environment-providers`, {
    body: JSON.stringify(data),
    method: 'POST',
  })
  return response.json()
}

export async function deleteProvider(teamId: string, providerId: string): Promise<void> {
  await fetchWithAuth(`/api/v1/teams/${teamId}/environment-providers/${providerId}`, {
    method: 'DELETE',
  })
}

export async function getProviders(teamId: string): Promise<EnvironmentProviderDto[]> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/environment-providers`)
  return response.json()
}

export async function updateProvider(
  teamId: string,
  providerId: string,
  data: UpdateProviderData,
): Promise<EnvironmentProviderDto> {
  const response = await fetchWithAuth(
    `/api/v1/teams/${teamId}/environment-providers/${providerId}`,
    {
      body: JSON.stringify(data),
      method: 'PUT',
    },
  )
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

import type {
  CreateModelProviderRequest,
  ModelEntryDto,
  ModelProviderDto,
  SupportedProviderType,
  UpdateModelProviderRequest,
} from '@/types/auth-types'

import { fetchWithAuth } from './auth-api'

export async function createModelProvider(
  teamId: string,
  data: CreateModelProviderRequest,
): Promise<ModelProviderDto> {
  const response = await fetchWithAuth(`/api/v1/model-providers/teams/${teamId}`, {
    body: JSON.stringify(data),
    method: 'POST',
  })
  return response.json()
}

export async function deleteModelProvider(teamId: string, providerId: string): Promise<void> {
  await fetchWithAuth(`/api/v1/model-providers/teams/${teamId}/${providerId}`, {
    method: 'DELETE',
  })
}

export async function discoverModels(providerId: string): Promise<ModelEntryDto[]> {
  const response = await fetchWithAuth(`/api/v1/model-providers/${providerId}/discover-models`)
  return response.json()
}

export async function getSupportedTypes(): Promise<SupportedProviderType[]> {
  const response = await fetchWithAuth('/api/v1/model-providers/supported-types')
  return response.json()
}

export async function listModelProviders(teamId: string): Promise<ModelProviderDto[]> {
  const response = await fetchWithAuth(`/api/v1/model-providers/teams/${teamId}`)
  return response.json()
}

export async function testConnection(data: {
  apiKey?: string
  baseUrl?: string
  providerType: string
}): Promise<{ error?: string; models?: ModelEntryDto[]; success: boolean }> {
  const response = await fetchWithAuth('/api/v1/model-providers/test-connection', {
    body: JSON.stringify(data),
    method: 'POST',
  })
  return response.json()
}

export async function updateModelProvider(
  teamId: string,
  providerId: string,
  data: UpdateModelProviderRequest,
): Promise<ModelProviderDto> {
  const response = await fetchWithAuth(`/api/v1/model-providers/teams/${teamId}/${providerId}`, {
    body: JSON.stringify(data),
    method: 'PUT',
  })
  return response.json()
}

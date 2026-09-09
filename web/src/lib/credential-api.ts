import type {
  GitHubInstallationInfoDto,
  RemoteRepositoryDto,
  RepoCredentialDto,
  RepositoryDto,
  SaveRepoCredentialRequest,
} from '@/types/auth-types'

import { useAuthStore } from '@/store/auth-store'

import { ApiError } from './auth-api'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export async function createCredential(
  teamId: string,
  data: SaveRepoCredentialRequest,
): Promise<RepoCredentialDto> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/credentials`, {
    body: JSON.stringify(data),
    method: 'POST',
  })
  return response.json()
}

export async function deleteCredential(teamId: string, credentialId: string): Promise<void> {
  await fetchWithAuth(`/api/v1/teams/${teamId}/credentials/${credentialId}`, {
    method: 'DELETE',
  })
}

export async function generateSshKey(teamId: string, name: string): Promise<RepoCredentialDto> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/credentials/generate-ssh-key`, {
    body: JSON.stringify({ name }),
    method: 'POST',
  })
  return response.json()
}

export async function getAffectedRepositories(
  teamId: string,
  credentialId: string,
): Promise<RepositoryDto[]> {
  const response = await fetchWithAuth(
    `/api/v1/teams/${teamId}/credentials/${credentialId}/affected-repositories`,
  )
  return response.json()
}

export async function listAvailableRepos(
  teamId: string,
  credentialId: string,
): Promise<RemoteRepositoryDto[]> {
  const url = `/api/v1/teams/${teamId}/credentials/${credentialId}/available-repos`
  const response = await fetchWithAuth(url)
  return response.json()
}

export async function listCredentials(teamId: string): Promise<RepoCredentialDto[]> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/credentials`)
  return response.json()
}

export async function updateCredential(
  teamId: string,
  credentialId: string,
  data: SaveRepoCredentialRequest,
): Promise<RepoCredentialDto> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/credentials/${credentialId}`, {
    body: JSON.stringify(data),
    method: 'PUT',
  })
  return response.json()
}

export async function validateGitHubAppInstallation(
  teamId: string,
  installationId: string,
): Promise<GitHubInstallationInfoDto> {
  const response = await fetchWithAuth(
    `/api/v1/teams/${teamId}/credentials/validate-github-app-installation?installationId=${encodeURIComponent(installationId)}`,
    { method: 'POST' },
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

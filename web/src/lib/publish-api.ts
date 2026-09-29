import type {
  PublishCapabilities,
  PublishPrRequest,
  PullRequestResult,
  PushBranchRequest,
  PushBranchResponse,
} from '@/types/diff-types'

import { useAuthStore } from '@/store/auth-store'

import { ApiError } from './auth-api'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export async function downloadPatchFile(chatId: string, executionId: string): Promise<void> {
  const response = await fetchWithAuth(
    `/api/v1/chats/${chatId}/executions/${executionId}/diff/export`,
    {
      headers: {
        Accept: 'text/plain',
      },
    },
  )
  const patchText = await response.text()
  const blob = new Blob([patchText], { type: 'text/x-diff;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.setAttribute('download', `execution-${executionId}.patch`)
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

export async function fetchPublishCapabilities(
  chatId: string,
  executionId: string,
): Promise<PublishCapabilities> {
  const response = await fetchWithAuth(
    `/api/v1/chats/${chatId}/executions/${executionId}/publish-capabilities`,
  )
  return response.json()
}

export async function publishPullRequest(
  chatId: string,
  executionId: string,
  request: PublishPrRequest,
): Promise<PullRequestResult> {
  const response = await fetchWithAuth(
    `/api/v1/chats/${chatId}/executions/${executionId}/publish-pr`,
    {
      body: JSON.stringify(request),
      headers: {
        'Content-Type': 'application/json',
      },
      method: 'POST',
    },
  )
  return response.json()
}

export async function pushBranch(
  chatId: string,
  executionId: string,
  request: PushBranchRequest,
): Promise<PushBranchResponse> {
  const response = await fetchWithAuth(
    `/api/v1/chats/${chatId}/executions/${executionId}/push-branch`,
    {
      body: JSON.stringify(request),
      headers: {
        'Content-Type': 'application/json',
      },
      method: 'POST',
    },
  )
  return response.json()
}

async function fetchWithAuth(endpoint: string, options: RequestInit = {}): Promise<Response> {
  const { accessToken } = useAuthStore.getState()
  const headers: HeadersInit = {
    ...options.headers,
    ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
  }

  const response = await fetch(`${API_BASE_URL}${endpoint}`, {
    ...options,
    headers,
  })

  if (!response.ok) {
    let message = 'An error occurred'
    try {
      const data = await response.json()
      message = data.message || message
    } catch {
      message = response.statusText || message
    }
    throw new ApiError(message, response.status)
  }

  return response
}

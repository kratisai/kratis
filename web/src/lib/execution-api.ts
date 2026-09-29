import { useAuthStore } from '@/store/auth-store'

import { ApiError } from './auth-api'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export type AgentHarness = string

export interface AgentHarnessOption {
  name: string
  value: string
}

export interface CreateSandboxExecutionRequest {
  canvasId: string
  environmentId?: string
  harness: AgentHarness
  modelName: string
  modelProviderId: string
  providerId?: string
}

export type ExecutionStatus = 'COMPLETED' | 'FAILED' | 'IDLE' | 'RUNNING'

export interface SandboxExecutionDto {
  chatId: string
  completedAt?: null | string
  completionTokens?: null | number
  exitCode?: null | number
  harness?: string
  id: string
  promptTokens?: null | number
  startedAt: string
  status: ExecutionStatus
  taskPrompt?: string
  totalSpend?: null | number
  totalTokens?: null | number
  usageLastUpdatedAt?: null | string
}

export async function createSandboxExecution(
  chatId: string,
  data: CreateSandboxExecutionRequest,
): Promise<SandboxExecutionDto> {
  const response = await fetchWithAuth(`/api/v1/chats/${chatId}/executions`, {
    body: JSON.stringify(data),
    method: 'POST',
  })
  return response.json()
}

export async function fetchHarnesses(teamId: string): Promise<AgentHarnessOption[]> {
  const response = await fetchWithAuth(`/api/v1/teams/${teamId}/harnesses`)
  return response.json()
}

export async function listChatExecutions(chatId: string): Promise<SandboxExecutionDto[]> {
  const response = await fetchWithAuth(`/api/v1/chats/${chatId}/executions`)
  return response.json()
}

export async function terminateExecution(chatId: string, executionId: string): Promise<void> {
  await fetchWithAuth(`/api/v1/chats/${chatId}/executions/${executionId}/terminate`, {
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

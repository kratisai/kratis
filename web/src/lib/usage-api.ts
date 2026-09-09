import type { PageResponse, UsageLogEntry, UsageSummary } from '@/types/usage-types'

import { useAuthStore } from '@/store/auth-store'

import { ApiError } from './auth-api'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export async function fetchUsageLogs(
  teamId: string,
  params: {
    agent?: string
    model?: string
    page?: number
    size?: number
    timeframe?: string
    usageType?: string
  },
): Promise<PageResponse<UsageLogEntry>> {
  const query = new URLSearchParams()
  if (params.timeframe) query.append('timeframe', params.timeframe)
  if (params.usageType && params.usageType !== 'all') query.append('usageType', params.usageType)
  if (params.model) query.append('model', params.model)
  if (params.agent) query.append('agent', params.agent)
  if (params.page !== undefined) query.append('page', params.page.toString())
  if (params.size !== undefined) query.append('size', params.size.toString())

  const res = await fetchWithAuth(`/api/v1/teams/${teamId}/usage-logs?${query.toString()}`)
  return res.json()
}

export async function fetchUsageSummary(teamId: string, timeframe?: string): Promise<UsageSummary> {
  const params = new URLSearchParams()
  if (timeframe) params.append('timeframe', timeframe)
  const res = await fetchWithAuth(`/api/v1/teams/${teamId}/usage-summary?${params.toString()}`)
  return res.json()
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

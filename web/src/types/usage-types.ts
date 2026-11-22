import type { ModelUsageDto } from '@/types/auth-types'

export interface PageResponse<T> {
  content: T[]
  number: number
  size: number
  totalElements: number
  totalPages: number
}

export interface UsageLogEntry {
  activityTitle: string
  agentName: null | string
  durationSeconds: null | number
  id: string
  models: ModelUsageDto[]
  status: 'COMPLETED' | 'FAILED' | 'IDLE' | 'RUNNING' | 'UNKNOWN' | null
  targetUrl?: null | string
  timestamp: string
  usageType: UsageType
  userEmail: string
}

export interface UsageSummary {
  agentShareByCost: ShareBreakdown[]
  agentShareByTime: ShareBreakdown[]
  modelShare: ShareBreakdown[]
  timeSeries: TimeSeriesPoint[]
  totalCost: number
  totalOperations: number
  totalTokens: number
}

export type UsageType = 'CHAT' | 'EXECUTION' | 'INGESTION' | 'TEAM_WIKI'

interface ShareBreakdown {
  cost: number
  count: number
  label: string
  percentage: number
  tokens: number
}

interface TimeSeriesPoint {
  cost: number
  timestamp: string
  tokens: number
}

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
  modelIdentifiers: string[]
  status: 'COMPLETED' | 'FAILED' | 'IDLE' | 'RUNNING' | null
  targetUrl?: null | string
  timestamp: string
  totalSpend: number
  totalTokens: number
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

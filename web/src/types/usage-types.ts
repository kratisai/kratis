export interface PageResponse<T> {
  content: T[]
  number: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ShareBreakdown {
  cost: number
  count: number
  label: string
  percentage: number
  tokens: number
}

export interface TimeSeriesPoint {
  cost: number
  timestamp: string
  tokens: number
}

export interface UsageLogEntry {
  activityTitle: string
  agentName: null | string
  durationSeconds: null | number
  id: string
  modelIdentifier: string
  status: 'COMPLETED' | 'FAILED' | 'IDLE' | 'RUNNING' | null
  timestamp: string
  totalSpend: number
  totalTokens: number
  usageType: 'CHAT' | 'EXECUTION' | 'INGESTION'
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

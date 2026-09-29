import { useQuery } from '@tanstack/react-query'

import { fetchUsageLogs, fetchUsageSummary } from '@/lib/usage-api'

export function useUsageLogs(
  teamId: null | string,
  params: {
    agent?: string
    model?: string
    page?: number
    size?: number
    timeframe?: string
    usageType?: string
  },
) {
  return useQuery({
    enabled: !!teamId,
    queryFn: () => fetchUsageLogs(teamId as string, params),
    queryKey: ['team-usage-logs', teamId, params],
  })
}

export function useUsageSummary(teamId: null | string, timeframe?: string) {
  return useQuery({
    enabled: !!teamId,
    queryFn: () => fetchUsageSummary(teamId as string, timeframe),
    queryKey: ['team-usage-summary', teamId, timeframe],
  })
}

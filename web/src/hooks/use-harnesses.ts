import { useQuery } from '@tanstack/react-query'

import { type AgentHarnessOption, fetchHarnesses } from '@/lib/execution-api'
import { useAuthStore } from '@/store/auth-store'

const HARNESSES_QUERY_KEY = 'harnesses'

export function useHarnesses() {
  const teamId = useAuthStore().currentTeamId
  return useQuery<AgentHarnessOption[]>({
    enabled: !!teamId,
    queryFn: () => fetchHarnesses(teamId!),
    queryKey: [HARNESSES_QUERY_KEY, teamId],
  })
}

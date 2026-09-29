import { useQuery } from '@tanstack/react-query'

import { fetchGitHubAppInfo, fetchInstallationInfo } from '@/lib/config-api'

const CONFIG_QUERY_KEY = 'config'

export function useGitHubAppInfo(enabled = true) {
  return useQuery({
    enabled,
    queryFn: fetchGitHubAppInfo,
    queryKey: [CONFIG_QUERY_KEY, 'github-app'],
  })
}

export function useInstallationInfo(enabled = true) {
  return useQuery({
    enabled,
    queryFn: fetchInstallationInfo,
    queryKey: [CONFIG_QUERY_KEY, 'installation'],
  })
}

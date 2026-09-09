import { useMutation, useQueryClient } from '@tanstack/react-query'

import { TEAMS_QUERY_KEY } from '@/hooks/use-teams.ts'
import { login, refreshAccessToken, register } from '@/lib/auth-api'
import { useAuthStore } from '@/store/auth-store'

export function useLogin() {
  const queryClient = useQueryClient()
  const { login: authLogin } = useAuthStore.getState()

  return useMutation({
    mutationFn: login,
    onSuccess: async (data) => {
      const user = {
        email: data.user.email,
        id: data.user.id,
        name: data.user.displayName,
      }

      // Store auth data in Zustand (UI state only)
      authLogin(user, data.accessToken, data.refreshToken, data.expiresIn)

      // Invalidate teams query to trigger a refetch from TanStack Query cache
      await queryClient.invalidateQueries({ queryKey: [TEAMS_QUERY_KEY] })
    },
  })
}

export function useRefreshToken() {
  const { updateTokens } = useAuthStore.getState()

  return useMutation({
    mutationFn: refreshAccessToken,
    onSuccess: (data) => {
      updateTokens(data.accessToken, data.refreshToken, data.expiresIn)
    },
  })
}

export function useRegister() {
  return useMutation({
    mutationFn: register,
  })
}

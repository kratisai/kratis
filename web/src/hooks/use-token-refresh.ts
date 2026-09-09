import { useCallback, useEffect, useRef } from 'react'

import { useRefreshToken } from '@/hooks/use-auth'
import { useAuthStore } from '@/store/auth-store'

// Refresh tokens 5 minutes before expiry
const REFRESH_BUFFER_MS = 5 * 60 * 1000

export function useTokenRefresh() {
  const { isTokenExpired, refreshToken, tokenExpiry } = useAuthStore()
  const { mutate: refresh } = useRefreshToken()
  const timerRef = useRef<null | ReturnType<typeof setTimeout>>(null)

  const scheduleRefresh = useCallback(
    (expiryTime: number) => {
      // Clear any existing timer
      if (timerRef.current) {
        clearTimeout(timerRef.current)
      }

      const timeUntilExpiry = expiryTime - Date.now()
      const refreshIn = Math.max(0, timeUntilExpiry - REFRESH_BUFFER_MS)

      if (refreshIn > 0 && refreshToken) {
        timerRef.current = setTimeout(() => {
          refresh({ refreshToken })
        }, refreshIn)
      }
    },
    [refreshToken, refresh],
  )

  useEffect(() => {
    // If not authenticated, nothing to do
    if (!refreshToken) {
      if (timerRef.current) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }
      return
    }

    // Check if token is already expired
    if (isTokenExpired()) {
      // Try to refresh immediately
      refresh({ refreshToken })
      return
    }

    // Schedule refresh for when token is about to expire
    if (tokenExpiry) {
      scheduleRefresh(tokenExpiry)
    }

    // Cleanup timer on unmount
    return () => {
      if (timerRef.current) {
        clearTimeout(timerRef.current)
      }
    }
  }, [refreshToken, isTokenExpired, tokenExpiry, refresh, scheduleRefresh])
}

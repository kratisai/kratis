import { useEffect, useRef } from 'react'

import { useRefreshToken } from '@/hooks/use-auth'
import { useAuthStore } from '@/store/auth-store'

const REFRESH_BUFFER_MS = 5 * 60 * 1000

export function useTokenRefresh() {
  const { isTokenExpired, refreshToken, tokenExpiry } = useAuthStore()
  const { mutate: refresh } = useRefreshToken()
  const timerRef = useRef<null | ReturnType<typeof setTimeout>>(null)

  useEffect(() => {
    if (!refreshToken) return

    if (isTokenExpired()) {
      refresh({ refreshToken })
      return
    }

    if (tokenExpiry) {
      const refreshIn = Math.max(0, tokenExpiry - Date.now() - REFRESH_BUFFER_MS)
      timerRef.current = setTimeout(() => refresh({ refreshToken }), refreshIn)
    }

    return () => {
      if (timerRef.current) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }
    }
  }, [refreshToken, isTokenExpired, tokenExpiry, refresh])
}

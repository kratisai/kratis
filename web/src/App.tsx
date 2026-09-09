import { RouterProvider } from '@tanstack/react-router'
import { useEffect } from 'react'

import { AuthDialog } from '@/components/auth/auth-dialog'
import { useTokenRefresh } from '@/hooks/use-token-refresh'
import { router } from '@/router'
import { useAuthStore } from '@/store/auth-store'
import { useWebSocketStore } from '@/store/websocket-store'

export default function App() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated)
  const connect = useWebSocketStore((state) => state.connect)
  const disconnect = useWebSocketStore((state) => state.disconnect)

  // Automatically refresh tokens when they're about to expire
  useTokenRefresh()

  // Manage WebSocket connection lifecycle globally when authenticated
  useEffect(() => {
    if (isAuthenticated) {
      connect()
      return () => {
        disconnect()
      }
    }
  }, [isAuthenticated, connect, disconnect])

  return (
    <>
      <AuthDialog open={!isAuthenticated} />
      {isAuthenticated && <RouterProvider router={router} />}
    </>
  )
}

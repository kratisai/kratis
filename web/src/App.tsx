import { RouterProvider } from '@tanstack/react-router'
import { useEffect, useRef } from 'react'

import { AuthDialog } from '@/components/auth/auth-dialog'
import { useTokenRefresh } from '@/hooks/use-token-refresh'
import { router } from '@/router'
import { useAuthStore } from '@/store/auth-store'
import { useWebSocketStore } from '@/store/websocket-store'

export default function App() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated)
  const connect = useWebSocketStore((state) => state.connect)
  const disconnect = useWebSocketStore((state) => state.disconnect)
  const prevAuthRef = useRef<boolean | null>(null)

  // Automatically refresh tokens when they're about to expire
  useTokenRefresh()

  // Drive the socket from auth-state transitions rather than mount/unmount, so
  // StrictMode's double-invoked effects don't tear down and recreate the connection.
  useEffect(() => {
    const prevAuth = prevAuthRef.current
    prevAuthRef.current = isAuthenticated

    if (isAuthenticated && !prevAuth) connect()
    else if (!isAuthenticated && prevAuth) disconnect()
  }, [isAuthenticated, connect, disconnect])

  return (
    <>
      <AuthDialog open={!isAuthenticated} />
      {isAuthenticated && <RouterProvider router={router} />}
    </>
  )
}

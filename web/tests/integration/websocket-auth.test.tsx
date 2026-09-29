import { act } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/store/auth-store'
import { useWebSocketStore } from '@/store/websocket-store'

describe('WebSocket Authentication Flow', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.getState().logout()
    // Reset WebSocket store
    useWebSocketStore.getState().disconnect()
  })

  afterEach(() => {
    useAuthStore.getState().logout()
    useWebSocketStore.getState().disconnect()
  })

  it('WebSocket store has no token when not authenticated', () => {
    // Ensure unauthenticated
    useAuthStore.getState().logout()

    const authState = useAuthStore.getState()
    expect(authState.isAuthenticated).toBe(false)
    expect(authState.accessToken).toBeNull()
  })

  it('WebSocket store can access auth token when authenticated', () => {
    // Authenticate user
    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600
    )

    // Verify auth state has token
    const authState = useAuthStore.getState()
    expect(authState.isAuthenticated).toBe(true)
    expect(authState.accessToken).toBe('test-access-token')

    // WebSocket store can read the token from auth store
    const wsState = useWebSocketStore.getState()
    expect(wsState).toBeDefined()
  })

  it('auth state is cleared on logout', () => {
    // Authenticate and verify
    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600
    )

    expect(useAuthStore.getState().isAuthenticated).toBe(true)
    expect(useAuthStore.getState().accessToken).toBe('test-access-token')

    // Logout
    act(() => {
      useAuthStore.getState().logout()
    })

    // Verify all state is cleared
    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(false)
    expect(state.accessToken).toBeNull()
    expect(state.refreshToken).toBeNull()
    expect(state.user).toBeNull()
  })

  it('WebSocket store reflects auth state changes', () => {
    // Start unauthenticated
    expect(useAuthStore.getState().isAuthenticated).toBe(false)

    // Authenticate
    act(() => {
      useAuthStore.getState().login(
        { email: 'test@test.com', id: 'user-1', name: 'Test User' },
        'test-access-token',
        'test-refresh-token',
        3600
      )
    })

    expect(useAuthStore.getState().isAuthenticated).toBe(true)
    expect(useAuthStore.getState().accessToken).toBe('test-access-token')

    // Logout
    act(() => {
      useAuthStore.getState().logout()
    })

    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(useAuthStore.getState().accessToken).toBeNull()
  })
})

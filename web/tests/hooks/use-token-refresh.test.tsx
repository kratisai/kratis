import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/store/auth-store'

// Define mock at module level so it's available when vi.mock is hoisted
const mockRefresh = vi.fn()

vi.mock('@/hooks/use-auth', () => ({
  useRefreshToken: () => ({
    mutate: mockRefresh,
  }),
}))

// Mock sonner toast
vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

// Dynamic import after mocks are set up
const { useTokenRefresh } = await import('@/hooks/use-token-refresh')

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { retry: false },
      queries: { retry: false },
    },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
}

describe('useTokenRefresh', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    // Reset auth store to unauthenticated state
    useAuthStore.getState().logout()
  })

  afterEach(() => {
    vi.useRealTimers()
    // Clean up store
    useAuthStore.getState().logout()
  })

  it('does nothing when not authenticated', async () => {
    act(() => {
      renderHook(() => useTokenRefresh(), { wrapper: createWrapper() })
    })

    // Advance time past any potential refresh
    vi.advanceTimersByTime(10000)

    // Let any pending state updates settle
    await vi.runAllTimersAsync()

    expect(mockRefresh).not.toHaveBeenCalled()
  })

  it('refreshes immediately when token is expired', async () => {
    // Set up authenticated state with expired token
    useAuthStore.getState().login(
      { email: 'test@test.com', id: '1', name: 'Test' },
      'expired-access',
      'valid-refresh',
      -1000 // negative expiresIn = already expired
    )
    useAuthStore.getState().setCurrentTeamId('1')

    act(() => {
      renderHook(() => useTokenRefresh(), { wrapper: createWrapper() })
    })

    // Let any pending state updates settle
    await vi.runAllTimersAsync()

    // Should have called refresh immediately
    expect(mockRefresh).toHaveBeenCalledWith({ refreshToken: 'valid-refresh' })
  })

  it('schedules refresh before token expiry', async () => {
    // Use a long expiry time (10 minutes = 600 seconds)
    // Refresh buffer is 5 minutes (300 seconds), so refresh should fire at 5 minutes
    const expiresIn = 600
    useAuthStore.getState().login(
      { email: 'test@test.com', id: '1', name: 'Test' },
      'valid-access',
      'valid-refresh',
      expiresIn
    )
    useAuthStore.getState().setCurrentTeamId('1')

    act(() => {
      renderHook(() => useTokenRefresh(), { wrapper: createWrapper() })
    })

    // No call yet - waiting for refresh buffer
    expect(mockRefresh).not.toHaveBeenCalled()

    // Advance time to 4 minutes 59 seconds (just before refresh should fire)
    act(() => {
      vi.advanceTimersByTime(299000)
    })
    expect(mockRefresh).not.toHaveBeenCalled()

    // Advance past the 5 minute mark
    act(() => {
      vi.advanceTimersByTime(2000)
    })

    // Let any pending state updates settle
    await vi.runAllTimersAsync()

    expect(mockRefresh).toHaveBeenCalledWith({ refreshToken: 'valid-refresh' })
  })

  it('cleans up timer on unmount', async () => {
    const expiresIn = 600 // 10 minutes
    useAuthStore.getState().login(
      { email: 'test@test.com', id: '1', name: 'Test' },
      'valid-access',
      'valid-refresh',
      expiresIn
    )
    useAuthStore.getState().setCurrentTeamId('1')

    const { unmount } = renderHook(() => useTokenRefresh(), { wrapper: createWrapper() })

    unmount()

    // Advance time past when refresh would have fired
    act(() => {
      vi.advanceTimersByTime(600000)
    })

    // Let any pending state updates settle
    await vi.runAllTimersAsync()

    // Should not have called refresh after unmount
    expect(mockRefresh).not.toHaveBeenCalled()
  })

  it('stops refreshing after logout', async () => {
    const expiresIn = 600
    useAuthStore.getState().login(
      { email: 'test@test.com', id: '1', name: 'Test' },
      'valid-access',
      'valid-refresh',
      expiresIn
    )
    useAuthStore.getState().setCurrentTeamId('1')

    const { rerender } = renderHook(() => useTokenRefresh(), { wrapper: createWrapper() })

    // Logout
    act(() => {
      useAuthStore.getState().logout()
    })
    rerender()

    // Advance time past refresh
    act(() => {
      vi.advanceTimersByTime(600000)
    })

    // Let any pending state updates settle
    await vi.runAllTimersAsync()

    // Should not have called refresh
    expect(mockRefresh).not.toHaveBeenCalled()
  })
})

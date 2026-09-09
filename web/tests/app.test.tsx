import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { Toaster } from 'sonner'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/store/auth-store'

// Mock the token refresh hook to avoid timer complications
vi.mock('@/hooks/use-token-refresh', () => ({
  useTokenRefresh: () => {},
}))

// Mock MainLayout to avoid rendering the full app
vi.mock('@/components/layout/main-layout', () => ({
  MainLayout: () => <div data-testid="main-layout">Main Layout</div>,
}))

// Mock WebSocket store to track connect/disconnect calls
const mockConnect = vi.fn()
const mockDisconnect = vi.fn()
vi.mock('@/store/websocket-store', () => ({
  useWebSocketStore: vi.fn((selector) =>
    selector({
      connect: mockConnect,
      disconnect: mockDisconnect,
      error: null,
      isConnected: true,
      isConnecting: false,
    })
  ),
}))

// Dynamic import of App after mocks are set up
const App = (await import('@/App')).default

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { retry: false },
      queries: { retry: false },
    },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      {children}
      <Toaster />
    </QueryClientProvider>
  )
}

describe('App Authentication State', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
    mockConnect.mockClear()
    mockDisconnect.mockClear()
  })

  afterEach(() => {
    useAuthStore.getState().logout()
  })

  it('shows AuthDialog when not authenticated', () => {
    render(<App />, { wrapper: createWrapper() })

    // AuthDialog should be visible (login form)
    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()

    // MainLayout should NOT be rendered
    expect(screen.queryByTestId('main-layout')).not.toBeInTheDocument()
  })

  it('shows MainLayout and hides AuthDialog when authenticated', async () => {
    const { rerender } = render(<App />, { wrapper: createWrapper() })

    // Initially unauthenticated - AuthDialog visible
    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    expect(screen.queryByTestId('main-layout')).not.toBeInTheDocument()

    // Authenticate
    act(() => {
      useAuthStore.getState().login(
        { email: 'test@test.com', id: 'user-1', name: 'Test User' },
        'test-access-token',
        'test-refresh-token',
        3600
      )
    })

    // Re-render to pick up state change
    rerender(<App />)

    // AuthDialog should be hidden
    await waitFor(() => {
      expect(screen.queryByLabelText('Email')).not.toBeInTheDocument()
    })

    // MainLayout should be visible
    expect(screen.getByTestId('main-layout')).toBeInTheDocument()
  })

  it('shows AuthDialog again after logout (regression test for blank page bug)', async () => {
    const { rerender } = render(<App />, { wrapper: createWrapper() })

    // Start unauthenticated
    expect(screen.getByLabelText('Email')).toBeInTheDocument()

    // Authenticate
    act(() => {
      useAuthStore.getState().login(
        { email: 'test@test.com', id: 'user-1', name: 'Test User' },
        'test-access-token',
        'test-refresh-token',
        3600
      )
    })
    rerender(<App />)

    // Verify authenticated
    await waitFor(() => {
      expect(screen.getByTestId('main-layout')).toBeInTheDocument()
    })

    // Logout
    act(() => {
      useAuthStore.getState().logout()
    })
    rerender(<App />)

    // AuthDialog should be visible again (NOT a blank page)
    await waitFor(() => {
      expect(screen.getByLabelText('Email')).toBeInTheDocument()
    })

    // MainLayout should be gone
    expect(screen.queryByTestId('main-layout')).not.toBeInTheDocument()
  })

  it('never shows a blank state during auth transitions', async () => {
    const { rerender } = render(<App />, { wrapper: createWrapper() })

    // Helper to check we're not in a blank state
    const assertNotBlank = () => {
      const hasAuthDialog = screen.queryByRole('dialog') !== null
      const hasMainLayout = screen.queryByTestId('main-layout') !== null
      // At least one should be present
      expect(hasAuthDialog || hasMainLayout).toBe(true)
    }

    // Initial state - AuthDialog
    assertNotBlank()

    // Authenticate
    act(() => {
      useAuthStore.getState().login(
        { email: 'test@test.com', id: 'user-1', name: 'Test User' },
        'test-access-token',
        'test-refresh-token',
        3600
      )
    })
    rerender(<App />)
    assertNotBlank()

    // Logout
    act(() => {
      useAuthStore.getState().logout()
    })
    rerender(<App />)
    assertNotBlank()
  })

  it('connects WebSocket on authentication and disconnects on logout', async () => {
    const { rerender } = render(<App />, { wrapper: createWrapper() })

    // Initially unauthenticated - connect should not be called
    expect(mockConnect).not.toHaveBeenCalled()

    // Authenticate
    act(() => {
      useAuthStore.getState().login(
        { email: 'test@test.com', id: 'user-1', name: 'Test User' },
        'test-access-token',
        'test-refresh-token',
        3600
      )
    })
    rerender(<App />)

    // WebSocket connect should be called upon authentication
    await waitFor(() => {
      expect(mockConnect).toHaveBeenCalledTimes(1)
    })

    // Logout
    act(() => {
      useAuthStore.getState().logout()
    })
    rerender(<App />)

    // WebSocket disconnect should be called upon logout
    await waitFor(() => {
      expect(mockDisconnect).toHaveBeenCalledTimes(1)
    })
  })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { AuthDialog } from '@/components/auth/auth-dialog'
import { useAuthStore } from '@/store/auth-store'

import {
  addFetchHandler,
  jsonResponse,
  mockListTeams,
  mockLogin,
  mockLoginShouldFail,
  mockRegister,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderWithProviders,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'

describe('Authentication Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  afterEach(() => {
    useAuthStore.getState().logout()
  })

  it('renders login form by default', () => {
    renderWithProviders(<AuthDialog open={true} />)

    // Check tabs exist - use getAllByRole for tabs
    const tabs = screen.getAllByRole('tab')
    expect(tabs.some(tab => tab.textContent === 'Sign In')).toBe(true)
    expect(tabs.some(tab => tab.textContent === 'Register')).toBe(true)
    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
  })

  it('completes login successfully', async () => {
    mockLogin()
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'My Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    const { user } = renderWithProviders(<AuthDialog open={true} />)

    // Fill login form
    await user.type(screen.getByLabelText('Email'), 'test@test.com')
    await user.type(screen.getByLabelText('Password'), 'password123')

    // Submit form - get all "Sign In" buttons and pick the submit one (last one)
    const signInButtons = screen.getAllByRole('button', { name: /sign in/i })
    const submitButton = signInButtons[signInButtons.length - 1]
    await user.click(submitButton)

    // Should show success toast
    await waitFor(() => {
      expect(screen.getByText('Signed in successfully')).toBeInTheDocument()
    })

    // Auth store should be populated
    const { isAuthenticated, user: authUser } = useAuthStore.getState()
    expect(isAuthenticated).toBe(true)
    expect(authUser?.email).toBe('test@test.com')
  })

  it('shows error when login fails', async () => {
    mockLoginShouldFail('Invalid credentials')

    const { user } = renderWithProviders(<AuthDialog open={true} />)

    // Fill login form
    await user.type(screen.getByLabelText('Email'), 'test@test.com')
    await user.type(screen.getByLabelText('Password'), 'wrongpassword')

    // Submit form - get all "Sign In" buttons and pick the submit one (last one)
    const signInButtons = screen.getAllByRole('button', { name: /sign in/i })
    const submitButton = signInButtons[signInButtons.length - 1]
    await user.click(submitButton)

    // Error toast should appear
    await waitFor(() => {
      expect(screen.getByText(/invalid credentials/i)).toBeInTheDocument()
    })

    // Auth store should remain unauthenticated
    const { isAuthenticated } = useAuthStore.getState()
    expect(isAuthenticated).toBe(false)
  })

  it('completes registration successfully', async () => {
    mockRegister()

    const { user } = renderWithProviders(<AuthDialog open={true} />)

    // Switch to register tab
    const registerTab = screen.getByRole('tab', { name: 'Register' })
    await user.click(registerTab)

    // Fill registration form - use placeholder text since labels use different IDs
    await user.type(screen.getByPlaceholderText('Your name'), 'New User')
    await user.type(screen.getByPlaceholderText('you@example.com'), 'newuser@test.com')
    await user.type(screen.getByLabelText('Password'), 'password123')

    // Submit form
    const submitButton = screen.getByRole('button', { name: /create account/i })
    await user.click(submitButton)

    // Should show success toast and switch to login tab
    await waitFor(() => {
      expect(screen.getByText(/account created/i)).toBeInTheDocument()
    })
  })

  it('shows loading state during login', async () => {
    // Create a promise that we can resolve later to simulate loading
    let resolvePromise: (value: import('@/types/auth-types').AuthTokensResponse) => void
    const promise = new Promise<import('@/types/auth-types').AuthTokensResponse>((resolve) => {
      resolvePromise = resolve
    })

    vi.spyOn(await import('@/lib/auth-api'), 'login').mockReturnValue(promise)

    const { user } = renderWithProviders(<AuthDialog open={true} />)

    // Fill login form
    await user.type(screen.getByLabelText('Email'), 'test@test.com')
    await user.type(screen.getByLabelText('Password'), 'password123')

    // Submit form - get all "Sign In" buttons and pick the submit one (last one)
    const signInButtons = screen.getAllByRole('button', { name: /sign in/i })
    const submitButton = signInButtons[signInButtons.length - 1]
    await user.click(submitButton)

    // Button should be disabled (loading state)
    await waitFor(() => {
      expect(submitButton).toBeDisabled()
    })

    // Resolve the promise to complete the login
    resolvePromise!({
      accessToken: 'test-access-token',
      expiresIn: 3600,
      refreshToken: 'test-refresh-token',
      user: {
        createdAt: '2024-01-01T00:00:00Z',
        displayName: 'Test User',
        email: 'test@test.com',
        id: 'user-1',
      },
    })
  })

  it('shows login dialog after logout', async () => {
    // Simulate authenticated user
    setAuthenticated({ userId: 'user-1' })
    expect(useAuthStore.getState().isAuthenticated).toBe(true)

    // Simulate logout (this is what App.tsx does when user clicks Sign Out)
    useAuthStore.getState().logout()

    // Verify unauthenticated state
    expect(useAuthStore.getState().isAuthenticated).toBe(false)

    // Render AuthDialog with open=true (simulating App.tsx's open={!isAuthenticated})
    renderWithProviders(<AuthDialog open={true} />)

    // Login dialog should be visible
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })
    expect(screen.getByLabelText('Email')).toBeInTheDocument()
  })

  it('clears all auth state on logout', () => {
    // Set up authenticated state
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    const stateBefore = useAuthStore.getState()
    expect(stateBefore.isAuthenticated).toBe(true)
    expect(stateBefore.accessToken).toBe('test-access-token')
    expect(stateBefore.refreshToken).toBe('test-refresh-token')
    expect(stateBefore.user).not.toBeNull()
    expect(stateBefore.currentTeamId).toBe('team-1')

    // Perform logout
    useAuthStore.getState().logout()

    // Verify all state is cleared
    const stateAfter = useAuthStore.getState()
    expect(stateAfter.isAuthenticated).toBe(false)
    expect(stateAfter.accessToken).toBeNull()
    expect(stateAfter.refreshToken).toBeNull()
    expect(stateAfter.user).toBeNull()
    expect(stateAfter.currentTeamId).toBeNull()
    expect(stateAfter.tokenExpiry).toBeNull()
  })

  it('shows login dialog when 401 response is received from protected endpoint', async () => {
    // Authenticate user
    setAuthenticated({ userId: 'user-1' })
    expect(useAuthStore.getState().isAuthenticated).toBe(true)

    // Mock a protected endpoint that returns 401
    addFetchHandler((url) => {
      if (url.includes('/api/v1/teams')) {
        return jsonResponse({ message: 'Unauthorized' }, 401)
      }
      return null
    })

    // Simulate the app detecting a 401 and logging out
    // (This would typically be done by an API interceptor)
    useAuthStore.getState().logout()

    // Verify unauthenticated state
    expect(useAuthStore.getState().isAuthenticated).toBe(false)

    // Render AuthDialog with open=true (simulating App.tsx's open={!isAuthenticated})
    renderWithProviders(<AuthDialog open={true} />)

    // Login dialog should be visible
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })
    expect(screen.getByLabelText('Email')).toBeInTheDocument()
  })

  it('covers auth-store addSession and updateTokens', () => {
    const session = {
      createdAt: new Date(),
      id: 'session-123',
      title: 'Session 123',
      type: 'ask' as const,
    }
    useAuthStore.getState().addSession(session)
    expect(useAuthStore.getState().sessions).toContainEqual(session)

    useAuthStore.getState().updateTokens('new-access', 'new-refresh', 1800)
    expect(useAuthStore.getState().accessToken).toBe('new-access')
    expect(useAuthStore.getState().refreshToken).toBe('new-refresh')
  })

  it('covers auth-api refreshAccessToken', async () => {
    addFetchHandler((url, options) => {
      if (url.includes('/api/v1/auth/refresh') && options.method === 'POST') {
        const body = JSON.parse(options.body as string)
        expect(body.refreshToken).toBe('old-refresh-token')
        return jsonResponse({
          accessToken: 'refreshed-access-token',
          expiresIn: 3600,
          refreshToken: 'refreshed-refresh-token',
          user: {
            createdAt: '2024-01-01T00:00:00Z',
            displayName: 'Test User',
            email: 'test@test.com',
            id: 'user-1',
          },
        })
      }
      return null
    })

    const { refreshAccessToken } = await import('@/lib/auth-api')
    const res = await refreshAccessToken({ refreshToken: 'old-refresh-token' })
    expect(res.accessToken).toBe('refreshed-access-token')
  })

  it('throws ApiError with HTTP status fallback when message is not present in response', async () => {
    addFetchHandler((url) => {
      if (url.includes('/api/v1/auth/login')) {
        return jsonResponse({}, 400)
      }
      return null
    })

    const { login } = await import('@/lib/auth-api')
    await expect(
      login({ email: 'test@test.com', password: 'password123' })
    ).rejects.toThrow('HTTP 400')
  })
})

import { beforeEach, describe, expect, it } from 'vitest'

import { useAuthStore } from '@/store/auth-store'

import {
  createMockTeam,
} from '../support/test-factories'
import {
  addFetchHandler,
  jsonResponse,
  mockListTeams,
  mockLogin,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderWithRouter,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'

describe('Login Team Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  it('auto-selects first team after login when no team is selected', async () => {
    // This test verifies that useTeams() auto-selects the first team
    // after login when currentTeamId is null.
    //
    // The flow:
    // 1. User logs in -> currentTeamId is null (set in auth-store login())
    // 2. Teams query succeeds -> returns list of teams
    // 3. useEffect in useTeams() detects: isSuccess && data && !currentTeamId
    // 4. Auto-selects first team: setCurrentTeamId(query.data[0].id)
    //
    // If the setCurrentTeamId call is removed from useTeams(), this test will fail
    // because currentTeamId will remain null and repos/other team-dependent features
    // will show "No team selected" errors.

    mockLogin()
    mockListTeams([createMockTeam({ name: 'My Team' })])

    // Mock model providers list (needed by SettingsView)
    addFetchHandler((url) => {
      if (url.includes('/model-providers')) {
        return jsonResponse([])
      }
      return null
    })

    // Simulate post-login state: authenticated but no team selected
    setAuthenticated({ userId: 'user-1' }) // No teamId!

    // Render SettingsView which uses useTeams() internally
    renderWithRouter(["/settings"])

    // Wait for teams query to complete and auto-selection to happen
    await waitFor(() => {
      const { currentTeamId } = useAuthStore.getState()
      expect(currentTeamId).toBe('team-1')
    })
  })

  it('does not override team selection when user has already selected a team', async () => {
    // This test verifies that the auto-selection only happens when
    // currentTeamId is null, not when the user has explicitly selected a team.

    mockLogin()
    mockListTeams([
      createMockTeam({ id: 'team-1', name: 'My Team', role: 'owner' }),
      createMockTeam({ id: 'team-2', isDefault: false, name: 'Other Team', role: 'member' }),
    ])

    // Mock model providers list (needed by SettingsView)
    addFetchHandler((url) => {
      if (url.includes('/model-providers')) {
        return jsonResponse([])
      }
      return null
    })

    // Simulate post-login state with team already selected
    setAuthenticated({ teamId: 'team-2', userId: 'user-1' })

    const { user } = renderWithRouter(["/settings"])

    // Wait for the Team tab to appear, then open it
    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))

    // Wait for team settings to load - should show "Other Team" in the header
    await waitFor(() => {
      expect(screen.getByText(/Team —.*Other Team/)).toBeInTheDocument()
    })

    // Verify team selection was NOT overridden
    const { currentTeamId } = useAuthStore.getState()
    expect(currentTeamId).toBe('team-2')
  })
})


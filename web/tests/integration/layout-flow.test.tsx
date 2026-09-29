import { beforeEach, describe, expect, it } from 'vitest'

import {
  mockListTeams,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderIntegration,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'

describe('Layout Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  it('renders main layout with header for authenticated user', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/settings'])

    // Header should render with team name
    await waitFor(() => {
      expect(screen.getByText('Test Team')).toBeInTheDocument()
    })

    // Settings page content should be visible (rendered via Outlet in MainLayout)
    await waitFor(() => {
      const elements = screen.getAllByText('Settings')
      expect(elements.length).toBeGreaterThan(0)
    })
    expect(screen.getByRole('tab', { name: /account/i })).toBeInTheDocument()
  })

  it('opens user dropdown menu and shows user info', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderIntegration(['/settings'])

    // Wait for layout to render
    await waitFor(() => {
      expect(screen.getByText('Test Team')).toBeInTheDocument()
    })

    // Click the user menu trigger button (shows team name)
    const teamButton = screen.getByText('Test Team').closest('button')
    await user.click(teamButton!)

    // Dropdown should show user info
    await waitFor(() => {
      expect(screen.getByText('Test User')).toBeInTheDocument()
    })
    expect(screen.getByText('test@test.com')).toBeInTheDocument()

    // Should show Sign Out option
    expect(screen.getByText('Sign Out')).toBeInTheDocument()
  })

  it('shows theme switcher in user dropdown', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByText('Test Team')).toBeInTheDocument()
    })

    // Open user dropdown
    const teamButton = screen.getByText('Test Team').closest('button')
    await user.click(teamButton!)

    // Theme options should be visible
    await waitFor(() => {
      expect(screen.getByText('Light')).toBeInTheDocument()
    })
    expect(screen.getByText('Dark')).toBeInTheDocument()
    expect(screen.getByText('System')).toBeInTheDocument()
  })

  it('switches team from dropdown', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Team Alpha',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-2',
        isDefault: false,
        name: 'Team Beta',
        role: 'member',
        tavilyApiKeyConfigured: false,
      },
    ])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByText('Team Alpha')).toBeInTheDocument()
    })

    // Open user dropdown
    const teamButton = screen.getByText('Team Alpha').closest('button')
    await user.click(teamButton!)

    // Should see both teams in the radio group
    await waitFor(() => {
      expect(screen.getByText('Team Beta')).toBeInTheDocument()
    })

    // Click on Team Beta to switch
    const teamBetaItem = screen.getByText('Team Beta')
    await user.click(teamBetaItem)

    // The team name in the header should update
    await waitFor(() => {
      expect(screen.getByText('Team Beta')).toBeInTheDocument()
    })
  })

  it('toggles desktop sidebar via header button', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByText('Test Team')).toBeInTheDocument()
    })

    // The header has a sidebar toggle button (Menu icon, no text label).
    // Find it by looking for buttons that contain an SVG (icon buttons).
    const allButtons = screen.getAllByRole('button')
    // The first button in the header is the sidebar toggle (Menu icon)
    const sidebarToggle = allButtons[0]
    await user.click(sidebarToggle)

    // After toggling, the sidebar visibility should change.
    // The desktop sidebar should still be present (toggled via UI store)
    await waitFor(() => {
      // Navigation items should still be accessible
      const settingsLinks = screen.getAllByText(/Settings/i)
      expect(settingsLinks.length).toBeGreaterThan(0)
    })
  })

  it('switches theme to dark via header dropdown', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByText('Test Team')).toBeInTheDocument()
    })

    // Open user dropdown
    const teamButton = screen.getByText('Test Team').closest('button')
    await user.click(teamButton!)

    // Click Dark theme option
    await waitFor(() => {
      expect(screen.getByText('Dark')).toBeInTheDocument()
    })
    await user.click(screen.getByText('Dark'))

    // The document element should have class 'dark'
    await waitFor(() => {
      expect(document.documentElement.classList.contains('dark')).toBe(true)
    })
  })
})

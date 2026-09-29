import { within } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'

import type { AddTeamMemberRequest, UpdateTeamRequest } from '@/types/auth-types'

import {
  addFetchHandler,
  jsonResponse,
  mockListTeams,
  mockUpdateProfile,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderWithRouter,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'

describe('Settings Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  it('shows settings view with profile form', async () => {
    mockListTeams([])

    // Pre-set teamId to avoid auto-selection interfering with this test
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderWithRouter(["/settings"])

    // Profile form should be visible
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Settings' })).toBeInTheDocument()
      expect(screen.getByText('Profile')).toBeInTheDocument()
    })
  })

  it('displays team settings when a team is selected', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        description: 'Test team description',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    // Mock getTeam detail endpoint - must come before model providers handler
    addFetchHandler((url) => {
      // Match /api/v1/teams/team-1 exactly (not model-providers sub-path)
      if (url === '/api/v1/teams/team-1') {
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: 'Test team description',
          id: 'team-1',
          isDefault: true,
          members: [
            {
              displayName: 'Test User',
              email: 'test@test.com',
              id: 'member-1',
              role: 'owner',
              userId: 'user-1',
            },
          ],
          name: 'Test Team',
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      return null
    })

    // Mock model providers list
    addFetchHandler((url) => {
      if (url.includes('/model-providers')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(["/settings"])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))

    await waitFor(() => {
      expect(screen.getByText('General Settings')).toBeInTheDocument()
      expect(screen.getByText('Team Members')).toBeInTheDocument()
    })
  })

  it('does not show team settings when no team is selected', async () => {
    mockListTeams([])

    // No team selected
    setAuthenticated({ userId: 'user-1' })
    renderWithRouter(["/settings"])

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Settings' })).toBeInTheDocument()
      expect(screen.getByText('Profile')).toBeInTheDocument()
    })

    // Team settings section should not be visible (only personal settings)
    expect(screen.queryByText('Team Settings')).not.toBeInTheDocument()
    expect(screen.queryByText('Create Team')).not.toBeInTheDocument()
  })

  it('opens create team dialog when clicking Create Team', async () => {
    // Mock list teams FIRST
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

    // Mock getTeam detail endpoint
    addFetchHandler((url) => {
      if (url === '/api/v1/teams/team-1') {
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: 'Test team',
          id: 'team-1',
          isDefault: true,
          members: [],
          name: 'Test Team',
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      return null
    })

    // Mock model providers list
    addFetchHandler((url) => {
      if (url.includes('/model-providers')) {
        return jsonResponse([])
      }
      return null
    })

    // Pre-set teamId to avoid auto-selection interfering with this test
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(["/settings"])

    // Wait for team settings to appear (proves team was loaded)
    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))

    await waitFor(() => {
      expect(screen.getByText(/Team —/)).toBeInTheDocument()
    })

    // Click Create Team button
    const createButton = screen.getByRole('button', { name: /create team/i })
    await user.click(createButton)

    // Dialog should open
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Create Team' })).toBeInTheDocument()
    })
  })

  it('shows create team button in team settings', async () => {
    // Mock list teams with GET method check only
    addFetchHandler((url, options) => {
      if ((url === '/api/v1/teams' || url === '/api/v1/teams/') && (!options.method || options.method === 'GET')) {
        return jsonResponse([
          {
            createdAt: '2024-01-01T00:00:00Z',
            id: 'team-1',
            name: 'Test Team',
            role: 'owner',
            updatedAt: '2024-01-01T00:00:00Z',
          },
        ])
      }
      return null
    })

    // Mock getTeam detail endpoint
    addFetchHandler((url) => {
      if (url === '/api/v1/teams/team-1') {
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: 'Test team',
          id: 'team-1',
          isDefault: true,
          members: [],
          name: 'Test Team',
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      return null
    })

    // Mock model providers list
    addFetchHandler((url) => {
      if (url.includes('/model-providers')) {
        return jsonResponse([])
      }
      return null
    })

    // Pre-set teamId to avoid auto-selection interfering with this test
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(["/settings"])

    // Wait for team settings to appear
    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))

    await waitFor(() => {
      expect(screen.getByText(/Team —/)).toBeInTheDocument()
    })

    // Create Team button should be visible in team settings section
    const createButton = screen.getByRole('button', { name: /create team/i })
    expect(createButton).toBeInTheDocument()
  })

  it('updates profile successfully', async () => {
    mockListTeams([])
    mockUpdateProfile()

    // Pre-set teamId to avoid auto-selection interfering with this test
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(["/settings"])

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Settings' })).toBeInTheDocument()
    })

    // Update name field
    const nameInput = screen.getByLabelText('Name')
    await user.clear(nameInput)
    await user.type(nameInput, 'Updated Name')

    // Submit form
    const saveButton = screen.getByRole('button', { name: /save changes/i })
    await user.click(saveButton)

    // Dialog should remain closed (no errors)
    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: /error/i })).not.toBeInTheDocument()
    })
  })

  it('hides owner-only buttons when user is a member (not owner)', async () => {
    // Mock list teams with member role
    addFetchHandler((url, options) => {
      if ((url === '/api/v1/teams' || url === '/api/v1/teams/') && (!options.method || options.method === 'GET')) {
        return jsonResponse([
          {
            createdAt: '2024-01-01T00:00:00Z',
            id: 'team-1',
            isDefault: false,
            name: 'Test Team',
            role: 'member',
            updatedAt: '2024-01-01T00:00:00Z',
          },
        ])
      }
      return null
    })

    // Mock getTeam detail endpoint with member role
    addFetchHandler((url) => {
      if (url === '/api/v1/teams/team-1') {
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: 'Test team',
          id: 'team-1',
          isDefault: false,
          members: [
            {
              displayName: 'Owner User',
              email: 'owner@test.com',
              id: 'member-1',
              role: 'owner',
              userId: 'user-2',
            },
            {
              displayName: 'Test User',
              email: 'test@test.com',
              id: 'member-2',
              role: 'member',
              userId: 'user-1',
            },
          ],
          name: 'Test Team',
          role: 'member',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      return null
    })

    // Mock model providers list
    addFetchHandler((url) => {
      if (url.includes('/model-providers')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(["/settings"])

    // Wait for the Team tab to appear, then open it
    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))

    await waitFor(() => {
      expect(screen.getByText(/Team —/)).toBeInTheDocument()
    })

    // Owner-only buttons should NOT be visible
    expect(screen.queryByRole('button', { name: /invite member/i })).not.toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: /models/i }))
    await waitFor(() => {
      expect(screen.queryAllByRole('button', { name: /add provider/i }).length).toBe(0)
    })
  })

  it('shows owner buttons when user is owner', async () => {
    // Mock list teams with owner role
    addFetchHandler((url, options) => {
      if ((url === '/api/v1/teams' || url === '/api/v1/teams/') && (!options.method || options.method === 'GET')) {
        return jsonResponse([
          {
            createdAt: '2024-01-01T00:00:00Z',
            id: 'team-1',
            isDefault: true,
            name: 'Test Team',
            role: 'owner',
            updatedAt: '2024-01-01T00:00:00Z',
          },
        ])
      }
      return null
    })

    // Mock getTeam detail endpoint with owner role
    addFetchHandler((url) => {
      if (url === '/api/v1/teams/team-1') {
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: 'Test team',
          id: 'team-1',
          isDefault: true,
          members: [
            {
              displayName: 'Test User',
              email: 'test@test.com',
              id: 'member-1',
              role: 'owner',
              userId: 'user-1',
            },
          ],
          name: 'Test Team',
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      return null
    })

    // Mock model providers list
    addFetchHandler((url) => {
      if (url.includes('/model-providers')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(["/settings"])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /invite member/i })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('tab', { name: /models/i }))
    await waitFor(() => {
      expect(screen.getAllByRole('button', { name: /add provider/i }).length).toBeGreaterThanOrEqual(1)
    })
  })

  it('updates team settings successfully', async () => {
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

    let putPayload: null | UpdateTeamRequest = null
    addFetchHandler((url, options) => {
      if (url === '/api/v1/teams/team-1' && options.method === 'PUT') {
        putPayload = JSON.parse(options.body as string)
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: putPayload!.description,
          id: 'team-1',
          name: putPayload!.name,
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      if (url === '/api/v1/teams/team-1' && (!options.method || options.method === 'GET')) {
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: 'Original description',
          id: 'team-1',
          isDefault: true,
          members: [],
          name: 'Test Team',
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      if (url.includes('/model-providers')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(["/settings"])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))

    await waitFor(() => {
      expect(screen.getByLabelText('Team Name')).toBeInTheDocument()
    })

    const nameInput = screen.getByLabelText('Team Name')
    await user.clear(nameInput)
    await user.type(nameInput, 'Fully Updated Team')

    const descInput = screen.getByLabelText('Description')
    await user.clear(descInput)
    await user.type(descInput, 'New description here')

    const generalSettingsCard = screen.getByText('General Settings').closest('.rounded-xl') || screen.getByText('General Settings').closest('div')
    const saveButton = within(generalSettingsCard as HTMLElement).getByRole('button', { name: /save changes/i })
    await user.click(saveButton)

    await waitFor(() => {
      expect(screen.getByText('Team updated successfully')).toBeInTheDocument()
    })

    expect(putPayload).toEqual({
      description: 'New description here',
      name: 'Fully Updated Team',
      teamId: 'team-1',
    })
  })

  it('shows error when updating team settings fails', async () => {
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

    addFetchHandler((url, options) => {
      if (url === '/api/v1/teams/team-1' && options.method === 'PUT') {
        return jsonResponse({ message: 'Team name conflict error' }, 409)
      }
      if (url === '/api/v1/teams/team-1' && (!options.method || options.method === 'GET')) {
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: 'Original description',
          id: 'team-1',
          isDefault: true,
          members: [],
          name: 'Test Team',
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      if (url.includes('/model-providers')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(["/settings"])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))

    await waitFor(() => {
      expect(screen.getByLabelText('Team Name')).toBeInTheDocument()
    })

    const nameInput = screen.getByLabelText('Team Name')
    await user.clear(nameInput)
    await user.type(nameInput, 'Conflicting Name')

    const generalSettingsCard = screen.getByText('General Settings').closest('.rounded-xl') || screen.getByText('General Settings').closest('div')
    const saveButton = within(generalSettingsCard as HTMLElement).getByRole('button', { name: /save changes/i })
    await user.click(saveButton)

    await waitFor(() => {
      expect(screen.getByText('Team name conflict error')).toBeInTheDocument()
    })
  })

  it('invites a new team member successfully and displays them in the list', async () => {
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

    let getTeamCallCount = 0
    let invitePayload: AddTeamMemberRequest | null = null

    addFetchHandler((url, options) => {
      if (url === '/api/v1/teams/team-1' && (!options.method || options.method === 'GET')) {
        getTeamCallCount++
        // On first call, user-1 is the only member
        if (getTeamCallCount === 1) {
          return jsonResponse({
            createdAt: '2024-01-01T00:00:00Z',
            description: 'Test team',
            id: 'team-1',
            isDefault: true,
            members: [
              {
                displayName: 'Test User',
                email: 'test@test.com',
                id: 'member-1',
                role: 'owner',
                userId: 'user-1',
              },
            ],
            name: 'Test Team',
            role: 'owner',
            updatedAt: '2024-01-01T00:00:00Z',
          })
        }
        // After invitation query invalidation, both user-1 and newmember are members
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: 'Test team',
          id: 'team-1',
          isDefault: true,
          members: [
            {
              displayName: 'Test User',
              email: 'test@test.com',
              id: 'member-1',
              role: 'owner',
              userId: 'user-1',
            },
            {
              displayName: 'newmember@example.com',
              email: 'newmember@example.com',
              id: 'member-new',
              role: 'member',
              userId: 'user-new',
            },
          ],
          name: 'Test Team',
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      if (url === '/api/v1/teams/team-1/members' && options.method === 'POST') {
        invitePayload = JSON.parse(options.body as string)
        return jsonResponse({
          displayName: invitePayload!.email,
          email: invitePayload!.email,
          id: 'member-new',
          role: invitePayload!.role,
          userId: 'user-new',
        }, 201)
      }
      if (url.includes('/model-providers')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(["/settings"])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /invite member/i })).toBeInTheDocument()
    })

    // Click "Invite Member"
    await user.click(screen.getByRole('button', { name: /invite member/i }))

    // Dialog should open
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Invite Team Member' })).toBeInTheDocument()
    })

    const dialog = screen.getByRole('dialog')
    // Type email
    const emailInput = within(dialog).getByPlaceholderText('colleague@example.com')
    await user.type(emailInput, 'newmember@example.com')

    // Submit
    const inviteButton = within(dialog).getByRole('button', { name: 'Invite Member' })
    await user.click(inviteButton)

    // Wait for success toast
    await waitFor(() => {
      expect(screen.getByText('Team member added successfully')).toBeInTheDocument()
    })

    // New member should show in the list
    await waitFor(() => {
      expect(screen.getAllByText('newmember@example.com').length).toBeGreaterThanOrEqual(1)
    })

    expect(invitePayload).toEqual({
      email: 'newmember@example.com',
      role: 'MEMBER',
    })
  })

  it('shows validation error when inviting member with empty/invalid email', async () => {
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

    addFetchHandler((url, options) => {
      if (url === '/api/v1/teams/team-1' && (!options.method || options.method === 'GET')) {
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: 'Test team',
          id: 'team-1',
          isDefault: true,
          members: [
            {
              displayName: 'Test User',
              email: 'test@test.com',
              id: 'member-1',
              role: 'owner',
              userId: 'user-1',
            },
          ],
          name: 'Test Team',
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      if (url.includes('/model-providers')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(["/settings"])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /invite member/i })).toBeInTheDocument()
    })

    // Click "Invite Member"
    await user.click(screen.getByRole('button', { name: /invite member/i }))

    // Dialog should open
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Invite Team Member' })).toBeInTheDocument()
    })

    const dialog = screen.getByRole('dialog')
    // Submit with empty email
    const inviteButton = within(dialog).getByRole('button', { name: 'Invite Member' })
    await user.click(inviteButton)

    // Form validation error should show
    await waitFor(() => {
      expect(screen.getByText('Please enter a valid email address')).toBeInTheDocument()
    })
  })

  it('removes team member successfully', async () => {
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

    let getTeamCallCount = 0
    let deleteCalled = false

    addFetchHandler((url, options) => {
      if (url === '/api/v1/teams/team-1' && (!options.method || options.method === 'GET')) {
        getTeamCallCount++
        if (getTeamCallCount === 1) {
          return jsonResponse({
            createdAt: '2024-01-01T00:00:00Z',
            description: 'Test team',
            id: 'team-1',
            isDefault: true,
            members: [
              {
                displayName: 'Test User',
                email: 'test@test.com',
                id: 'member-1',
                role: 'owner',
                userId: 'user-1',
              },
              {
                displayName: 'Other User',
                email: 'other@test.com',
                id: 'member-2',
                role: 'member',
                userId: 'user-2',
              },
            ],
            name: 'Test Team',
            role: 'owner',
            updatedAt: '2024-01-01T00:00:00Z',
          })
        }
        // After deletion query invalidation, user-2 is gone
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: 'Test team',
          id: 'team-1',
          isDefault: true,
          members: [
            {
              displayName: 'Test User',
              email: 'test@test.com',
              id: 'member-1',
              role: 'owner',
              userId: 'user-1',
            },
          ],
          name: 'Test Team',
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      if (url === '/api/v1/teams/team-1/members/user-2' && options.method === 'DELETE') {
        deleteCalled = true
        return new Response(null, { status: 200 })
      }
      if (url.includes('/model-providers')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(["/settings"])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))

    // Wait for the member to be displayed
    await waitFor(() => {
      expect(screen.getByText('Other User')).toBeInTheDocument()
    })

    // Click the Trash icon button for member-2 (user-2)
    const memberItem = screen.getByText('Other User').closest('li')
    const trashButton = memberItem?.querySelector('button')

    await user.click(trashButton!)

    // Success toast should appear
    await waitFor(() => {
      expect(screen.getByText('Team member removed successfully')).toBeInTheDocument()
    })

    // Member should be removed from document
    await waitFor(() => {
      expect(screen.queryByText('Other User')).not.toBeInTheDocument()
    })

    expect(deleteCalled).toBe(true)
  })

  it('updates Tavily API Key successfully', async () => {
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

    let patchPayload: null | { tavilyApiKey: null | string } = null
    addFetchHandler((url, options) => {
      if (url === '/api/v1/teams/team-1' && (!options.method || options.method === 'GET')) {
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: 'Test team',
          id: 'team-1',
          isDefault: true,
          members: [],
          name: 'Test Team',
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      if (url === '/api/v1/teams/team-1/tavily-api-key' && options.method === 'PATCH') {
        patchPayload = JSON.parse(options.body as string)
        return new Response(null, { status: 200 })
      }
      if (url.includes('/model-providers')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(["/settings"])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /integrations/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /integrations/i }))

    await waitFor(() => {
      expect(screen.getByLabelText(/Tavily API Key/i)).toBeInTheDocument()
    })

    const keyInput = screen.getByLabelText(/Tavily API Key/i)
    await user.type(keyInput, 'tvly-secretkey')

    const saveKeyButton = screen.getByRole('button', { name: /Save API Key/i })
    await user.click(saveKeyButton)

    await waitFor(() => {
      expect(screen.getByText('Tavily API key updated successfully')).toBeInTheDocument()
    })

    expect(patchPayload).toEqual({
      tavilyApiKey: 'tvly-secretkey',
    })
  })

  it('updates ingestion provider and model settings', async () => {
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

    let putPayload: null | UpdateTeamRequest = null
    addFetchHandler((url, options) => {
      if (url === '/api/v1/teams/team-1' && (!options.method || options.method === 'GET')) {
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: 'Test team',
          id: 'team-1',
          isDefault: true,
          members: [],
          name: 'Test Team',
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      if (url === '/api/v1/teams/team-1' && options.method === 'PUT') {
        putPayload = JSON.parse(options.body as string)
        return jsonResponse({
          createdAt: '2024-01-01T00:00:00Z',
          description: 'Test team',
          id: 'team-1',
          name: 'Test Team',
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      if (url.includes('/model-providers')) {
        return jsonResponse([
          {
            displayName: 'OpenAI',
            id: 'provider-1',
            models: [
              { kind: 'CHAT', modelName: 'gpt-4' },
              { kind: 'CHAT', modelName: 'gpt-3.5-turbo' },
            ],
            providerType: 'OPENAI',
          },
        ])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(["/settings"])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /models/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /models/i }))

    await waitFor(() => {
      expect(screen.getByLabelText(/Ingestion Provider/i)).toBeInTheDocument()
    })

    // Wait for providers list to load and render the OpenAI option
    const providerSelect = screen.getByLabelText(/Ingestion Provider/i)
    await waitFor(() => {
      expect(within(providerSelect).getByRole('option', { name: /OpenAI/i })).toBeInTheDocument()
    })

    // Select provider
    await user.selectOptions(providerSelect, 'provider-1')

    // Change model
    const modelSelect = screen.getByLabelText(/Ingestion Model/i)
    await user.selectOptions(modelSelect, 'gpt-4')

    const saveButton = screen.getByRole('button', { name: /save defaults/i })
    await user.click(saveButton)

    await waitFor(() => {
      expect(screen.getByText('Team updated successfully')).toBeInTheDocument()
    })

    expect(putPayload).toEqual({
      ingestionModel: 'gpt-4',
      ingestionProvider: 'provider-1',
      teamId: 'team-1',
    })
  })
})



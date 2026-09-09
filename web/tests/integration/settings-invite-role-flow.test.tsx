import { fireEvent, within } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'

import {
  addFetchHandler,
  jsonResponse,
  mockListTeams,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderWithRouter,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'

describe('Settings Invite Role Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  async function openInviteDialog() {
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
      if (url.includes('/model-providers')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const result = renderWithRouter(['/settings'])

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await result.user.click(screen.getByRole('tab', { name: /team/i }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /invite member/i })).toBeInTheDocument()
    })

    await result.user.click(screen.getByRole('button', { name: /invite member/i }))

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Invite Team Member' })).toBeInTheDocument()
    })

    return result
  }

  it('submits invitation with member role by default', async () => {
    let invitePayload: null | { email: string; role: string } = null
    addFetchHandler((url, options) => {
      if (url === '/api/v1/teams/team-1/members' && options.method === 'POST') {
        invitePayload = JSON.parse(options.body as string)
        return jsonResponse(
          {
            displayName: invitePayload!.email,
            email: invitePayload!.email,
            id: 'member-new',
            role: invitePayload!.role,
            userId: 'user-new',
          },
          201,
        )
      }
      return null
    })

    const { user } = await openInviteDialog()

    const dialog = screen.getByRole('dialog')
    const emailInput = within(dialog).getByPlaceholderText('colleague@example.com')
    await user.type(emailInput, 'member@example.com')

    await user.click(within(dialog).getByRole('button', { name: 'Invite Member' }))

    await waitFor(() => {
      expect(screen.getByText('Team member added successfully')).toBeInTheDocument()
    })

    expect(invitePayload).toEqual({ email: 'member@example.com', role: 'MEMBER' })
  })

  it('submits invitation with owner role selected', async () => {
    let invitePayload: null | { email: string; role: string } = null
    addFetchHandler((url, options) => {
      if (url === '/api/v1/teams/team-1/members' && options.method === 'POST') {
        invitePayload = JSON.parse(options.body as string)
        return jsonResponse(
          {
            displayName: invitePayload!.email,
            email: invitePayload!.email,
            id: 'member-owner',
            role: invitePayload!.role,
            userId: 'user-owner',
          },
          201,
        )
      }
      return null
    })

    const { user } = await openInviteDialog()

    const dialog = screen.getByRole('dialog')
    const emailInput = within(dialog).getByPlaceholderText('colleague@example.com')
    await user.type(emailInput, 'owner@example.com')

    const roleSelect = within(dialog).getByLabelText('Role')
    await user.selectOptions(roleSelect, 'owner')

    await user.click(within(dialog).getByRole('button', { name: 'Invite Member' }))

    await waitFor(() => {
      expect(screen.getByText('Team member added successfully')).toBeInTheDocument()
    })

    expect(invitePayload).toEqual({ email: 'owner@example.com', role: 'OWNER' })
  })

  it('shows validation error for invalid email address', async () => {
    const { user } = await openInviteDialog()

    const dialog = screen.getByRole('dialog')
    const emailInput = within(dialog).getByPlaceholderText('colleague@example.com')
    fireEvent.change(emailInput, { target: { value: 'invalid@example' } })

    await user.click(within(dialog).getByRole('button', { name: 'Invite Member' }))

    await waitFor(() => {
      expect(screen.getByText('Please enter a valid email address')).toBeInTheDocument()
    })
  })

  it('shows validation error for empty email address', async () => {
    const { user } = await openInviteDialog()

    const dialog = screen.getByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Invite Member' }))

    await waitFor(() => {
      expect(screen.getByText('Please enter a valid email address')).toBeInTheDocument()
    })
  })

  it('closes dialog when clicking cancel', async () => {
    const { user } = await openInviteDialog()

    const dialog = screen.getByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: 'Invite Team Member' })).not.toBeInTheDocument()
    })
  })
})

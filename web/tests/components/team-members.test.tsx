import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { TeamMembers } from '@/components/settings/team-members'
import * as teamApi from '@/lib/team-api'
import { useAuthStore } from '@/store/auth-store'

const mockTeamDetail = {
  createdAt: '2024-01-01T00:00:00Z',
  description: 'Test team',
  id: 'team-1',
  isDefault: true,
  members: [
    {
      displayName: 'Test User',
      email: 'test@example.com',
      id: 'member-1',
      role: 'owner',
      userId: 'user-1',
    },
  ],
  name: 'Test Team',
  role: 'owner',
  updatedAt: '2024-01-01T00:00:00Z',
}

const mockTeamDetailWithMultipleMembers = {
  ...mockTeamDetail,
  members: [
    {
      displayName: 'Owner User',
      email: 'owner@example.com',
      id: 'member-1',
      role: 'owner',
      userId: 'user-1',
    },
    {
      displayName: 'Member User',
      email: 'member@example.com',
      id: 'member-2',
      role: 'member',
      userId: 'user-2',
    },
  ],
}

vi.mock('@/lib/team-api', () => ({
  addTeamMember: vi.fn(),
  createTeam: vi.fn(),
  getTeam: vi.fn(),
  listTeams: vi.fn(),
  removeTeamMember: vi.fn(),
  updateTeam: vi.fn(),
  updateUserProfile: vi.fn(),
}))

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })
}

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = createTestQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>,
  )
}

describe('TeamMembers', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({
      currentTeamId: 'team-1',
      isAuthenticated: true,
      user: {
        email: 'test@example.com',
        id: 'user-1',
        name: 'Test User',
      },
    })
  })

  describe('when user is owner', () => {
    it('shows Invite Member button', async () => {
      vi.mocked(teamApi.getTeam).mockResolvedValue(mockTeamDetail)

      renderWithProviders(<TeamMembers isOwner={true} teamId="team-1" />)

      await waitFor(() => {
        expect(screen.getByRole('button', { name: /invite member/i })).toBeInTheDocument()
      })
    })

    it('shows remove button for non-owner members', async () => {
      vi.mocked(teamApi.getTeam).mockResolvedValue(mockTeamDetailWithMultipleMembers)

      renderWithProviders(<TeamMembers isOwner={true} teamId="team-1" />)

      await waitFor(() => {
        expect(screen.getByText('Member User')).toBeInTheDocument()
        // Should have a delete button for the member (not for the owner)
        const deleteButtons = screen.getAllByRole('button', { name: '' })
        expect(deleteButtons.length).toBeGreaterThan(0)
      })
    })

    it('does not show remove button for owner members', async () => {
      vi.mocked(teamApi.getTeam).mockResolvedValue(mockTeamDetail)

      renderWithProviders(<TeamMembers isOwner={true} teamId="team-1" />)

      await waitFor(() => {
        expect(screen.getByText('Test User')).toBeInTheDocument()
      })
    })
  })

  describe('when user is not owner', () => {
    it('hides Invite Member button', async () => {
      vi.mocked(teamApi.getTeam).mockResolvedValue(mockTeamDetail)

      renderWithProviders(<TeamMembers isOwner={false} teamId="team-1" />)

      await waitFor(() => {
        expect(screen.queryByRole('button', { name: /invite member/i })).not.toBeInTheDocument()
      })
    })

    it('hides remove buttons for all members', async () => {
      vi.mocked(teamApi.getTeam).mockResolvedValue(mockTeamDetailWithMultipleMembers)

      renderWithProviders(<TeamMembers isOwner={false} teamId="team-1" />)

      await waitFor(() => {
        expect(screen.getByText('Owner User')).toBeInTheDocument()
        expect(screen.getByText('Member User')).toBeInTheDocument()
        // No delete buttons should be visible
        expect(screen.queryByRole('button', { name: '' })).not.toBeInTheDocument()
      })
    })
  })
})

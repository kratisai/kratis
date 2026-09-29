import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type {TeamDetailDto, TeamDto} from "@/types/auth-types.ts";

import { SettingsView } from '@/components/views/settings-view'
import * as teamApi from '@/lib/team-api'
import { useAuthStore } from '@/store/auth-store'

const mockTeams = [
  {
    createdAt: '2024-01-01T00:00:00Z',
    description: 'Test team description',
    id: 'team-1',
    isDefault: true,
    name: 'Test Team',
    role: 'owner',
    tavilyApiKeyConfigured: false,
  } as TeamDto,
]

const mockTeamDetail = {
  ...mockTeams[0],
  members: [
    {
      displayName: 'Test User',
      email: 'test@example.com',
      id: 'member-1',
      role: 'owner',
      userId: 'user-1',
    },
  ],
  updatedAt: '2024-01-01T00:00:00Z',
} as TeamDetailDto

vi.mock('@/lib/team-api', () => ({
  addTeamMember: vi.fn(),
  createTeam: vi.fn(),
  getTeam: vi.fn(),
  listTeams: vi.fn(),
  removeTeamMember: vi.fn(),
  updateTeam: vi.fn(),
  updateUserProfile: vi.fn(),
}))

vi.mock('@/lib/model-provider-api', () => ({
  createModelProvider: vi.fn(),
  deleteModelProvider: vi.fn(),
  getSupportedTypes: vi.fn().mockResolvedValue([]),
  listModelProviders: vi.fn().mockResolvedValue([]),
  testConnection: vi.fn().mockResolvedValue({ models: [], success: true }),
  updateModelProvider: vi.fn(),
}))

vi.mock('@/lib/hitl-rule-api', () => ({
  createHitlRule: vi.fn(),
  deleteHitlRule: vi.fn(),
  listHitlRules: vi.fn().mockResolvedValue([]),
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

describe('SettingsView', () => {
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

  it('displays team settings when a team is selected', async () => {
    vi.mocked(teamApi.listTeams).mockResolvedValue(mockTeams)
    vi.mocked(teamApi.getTeam).mockResolvedValue(mockTeamDetail)

    const user = userEvent.setup()
    renderWithProviders(<SettingsView />)

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))

    await waitFor(() => {
      expect(screen.getByText('General Settings')).toBeInTheDocument()
      expect(screen.getByText('Team Members')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('tab', { name: /models/i }))
    await waitFor(() => {
      expect(screen.getByText('Model Providers')).toBeInTheDocument()
    })
  })

  it('shows team members when loaded', async () => {
    vi.mocked(teamApi.listTeams).mockResolvedValue(mockTeams)
    vi.mocked(teamApi.getTeam).mockResolvedValue(mockTeamDetail)

    const user = userEvent.setup()
    renderWithProviders(<SettingsView />)

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))

    await waitFor(() => {
      expect(screen.getByText('Test User')).toBeInTheDocument()
    })
  })

  it('does not show team settings when no team is selected', async () => {
    vi.mocked(teamApi.listTeams).mockResolvedValue([])

    useAuthStore.setState({ currentTeamId: null })
    renderWithProviders(<SettingsView />)

    await waitFor(() => {
      expect(screen.getByText('Settings')).toBeInTheDocument()
      expect(screen.getByText('Profile')).toBeInTheDocument()
      expect(screen.getByTestId('enquiries-banner')).toBeInTheDocument()
    })

    // Team settings section should not be visible
    expect(screen.queryByText('Team Settings')).not.toBeInTheDocument()
  })

  it('renders a horizontally scrollable tab list for narrow screens', async () => {
    vi.mocked(teamApi.listTeams).mockResolvedValue(mockTeams)
    vi.mocked(teamApi.getTeam).mockResolvedValue(mockTeamDetail)

    renderWithProviders(<SettingsView />)

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    const tablist = screen.getByRole('tablist')
    expect(tablist).toHaveClass('overflow-x-auto')
    expect(tablist).toHaveClass('w-full')
    expect(tablist).toHaveClass('justify-start')
  })

  it('allows the settings panel to grow wider than the previous fixed width', async () => {
    vi.mocked(teamApi.listTeams).mockResolvedValue(mockTeams)
    vi.mocked(teamApi.getTeam).mockResolvedValue(mockTeamDetail)

    renderWithProviders(<SettingsView />)

    expect(screen.getByTestId('settings-panel')).toHaveClass('max-w-6xl')
  })

  it('displays profile form with user data', async () => {
    vi.mocked(teamApi.listTeams).mockResolvedValue([])

    renderWithProviders(<SettingsView />)

    const nameInput = screen.getByRole('textbox', { name: /name/i })
    const emailInput = screen.getByRole('textbox', { name: /email/i })

    expect(nameInput).toHaveValue('Test User')
    expect(emailInput).toHaveValue('test@example.com')
  })

  it('shows Invite Member button when user is owner', async () => {
    vi.mocked(teamApi.listTeams).mockResolvedValue(mockTeams)
    vi.mocked(teamApi.getTeam).mockResolvedValue(mockTeamDetail)

    const user = userEvent.setup()
    renderWithProviders(<SettingsView />)

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /invite member/i })).toBeInTheDocument()
    })
  })

  it('shows Add Provider button when user is owner', async () => {
    vi.mocked(teamApi.listTeams).mockResolvedValue(mockTeams)
    vi.mocked(teamApi.getTeam).mockResolvedValue(mockTeamDetail)

    const user = userEvent.setup()
    renderWithProviders(<SettingsView />)

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /models/i })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('tab', { name: /models/i }))
    await waitFor(() => {
      expect(screen.getAllByRole('button', { name: /add provider/i }).length).toBeGreaterThanOrEqual(1)
    })
  })

  it('hides Invite Member button when user is not owner', async () => {
    const nonOwnerTeams = [
      {
        createdAt: '2024-01-01T00:00:00Z',
        description: 'Test team description',
        id: 'team-1',
        isDefault: false,
        name: 'Test Team',
        role: 'member',
        tavilyApiKeyConfigured: false,
        updatedAt: '2024-01-01T00:00:00Z',
      } as TeamDto,
    ]
    const nonOwnerTeamDetail = {
      ...nonOwnerTeams[0],
      members: [
        {
          displayName: 'Owner User',
          email: 'owner@example.com',
          id: 'member-1',
          role: 'owner',
          userId: 'user-2',
        },
        {
          displayName: 'Test User',
          email: 'test@example.com',
          id: 'member-2',
          role: 'member',
          userId: 'user-1',
        },
      ],
      updatedAt: '2024-01-01T00:00:00Z',
    }

    vi.mocked(teamApi.listTeams).mockResolvedValue(nonOwnerTeams)
    vi.mocked(teamApi.getTeam).mockResolvedValue(nonOwnerTeamDetail)

    const user = userEvent.setup()
    renderWithProviders(<SettingsView />)

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /team/i }))

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: /invite member/i })).not.toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: /models/i }))
    await waitFor(() => {
      expect(screen.queryAllByRole('button', { name: /add provider/i }).length).toBe(0)
    })
  })

  it('switches to HITL rules tab when clicked', async () => {
    vi.mocked(teamApi.listTeams).mockResolvedValue(mockTeams)
    vi.mocked(teamApi.getTeam).mockResolvedValue(mockTeamDetail)

    const user = userEvent.setup()
    renderWithProviders(<SettingsView />)

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /hitl rules/i })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('tab', { name: /hitl rules/i }))

    await waitFor(() => {
      expect(screen.getByText('HITL Rules')).toBeInTheDocument()
    })
  })

  it('opens enquiry dialog when clicked from the enquiries banner', async () => {
    vi.mocked(teamApi.listTeams).mockResolvedValue(mockTeams)
    vi.mocked(teamApi.getTeam).mockResolvedValue(mockTeamDetail)

    const user = userEvent.setup()
    renderWithProviders(<SettingsView />)

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /account/i })).toBeInTheDocument()
    })

    const enquiriesBanner = screen.getByTestId('enquiries-banner')
    expect(enquiriesBanner).toBeInTheDocument()
    expect(
      screen.getByText(
        /let us know your use case, or discuss paid options for support or features\./i,
      ),
    ).toBeInTheDocument()

    const enquiryButton = screen.getByRole('button', {
      name: /send an enquiry/i,
    })
    expect(enquiryButton).toBeInTheDocument()

    await user.click(enquiryButton)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /send an enquiry/i })).toBeInTheDocument()
      expect(screen.getByTestId('tally-iframe')).toBeInTheDocument()
    })
  })

  it('maintains enquiries banner visible when switching tabs', async () => {
    vi.mocked(teamApi.listTeams).mockResolvedValue(mockTeams)
    vi.mocked(teamApi.getTeam).mockResolvedValue(mockTeamDetail)

    const user = userEvent.setup()
    renderWithProviders(<SettingsView />)

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /team/i })).toBeInTheDocument()
    })

    expect(screen.getByTestId('enquiries-banner')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: /team/i }))
    expect(screen.getByTestId('enquiries-banner')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: /hitl rules/i }))
    expect(screen.getByTestId('enquiries-banner')).toBeInTheDocument()
  })
})

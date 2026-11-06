import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { TeamSkillDto } from '@/types/team-skill-types'

import { TeamSkillsPanel } from '@/components/settings/team-skills-panel'
import * as teamSkillApi from '@/lib/team-skill-api'
import { useAuthStore } from '@/store/auth-store'

vi.mock('@/lib/team-skill-api', () => ({
  deleteTeamSkill: vi.fn(),
  listTeamSkills: vi.fn(),
  updateTeamSkill: vi.fn(),
  uploadTeamSkill: vi.fn(),
}))

const mockSkills: TeamSkillDto[] = [
  {
    createdAt: '2026-09-06T12:00:00Z',
    description: 'Run the project linters',
    enabled: true,
    id: 'skill-1',
    name: 'lint',
    updatedAt: '2026-09-06T12:00:00Z',
  },
  {
    createdAt: '2026-09-06T12:00:00Z',
    description: 'Deploy to staging',
    enabled: false,
    id: 'skill-2',
    name: 'deploy',
    updatedAt: '2026-09-06T12:00:00Z',
  },
]

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

describe('TeamSkillsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({
      currentTeamId: 'team-1',
      isAuthenticated: true,
      user: {
        email: 'alice@example.com',
        id: 'user-1',
        name: 'Alice',
      },
    })
    vi.mocked(teamSkillApi.listTeamSkills).mockResolvedValue(mockSkills)
  })

  it('renders team skills with status', async () => {
    renderWithProviders(<TeamSkillsPanel />)

    await waitFor(() => {
      expect(screen.getByText('lint')).toBeInTheDocument()
    })

    const table = screen.getByRole('table')
    expect(within(table).getByText('deploy')).toBeInTheDocument()
    expect(within(table).getByText('Enabled')).toBeInTheDocument()
    expect(within(table).getByText('Disabled')).toBeInTheDocument()
  })

  it('toggles a skill enabled state', async () => {
    vi.mocked(teamSkillApi.updateTeamSkill).mockResolvedValue(mockSkills[0])
    const user = userEvent.setup()

    renderWithProviders(<TeamSkillsPanel />)
    await waitFor(() => expect(screen.getByText('lint')).toBeInTheDocument())

    await user.click(screen.getByRole('checkbox', { name: 'Toggle lint' }))

    await waitFor(() => {
      expect(teamSkillApi.updateTeamSkill).toHaveBeenCalledWith('team-1', 'skill-1', {
        enabled: false,
      })
    })
  })

  it('deletes a skill after confirmation', async () => {
    vi.mocked(teamSkillApi.deleteTeamSkill).mockResolvedValue(undefined)
    const user = userEvent.setup()

    renderWithProviders(<TeamSkillsPanel />)
    await waitFor(() => expect(screen.getByText('lint')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'Delete skill lint' }))
    const dialog = screen.getByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }))

    await waitFor(() => {
      expect(teamSkillApi.deleteTeamSkill).toHaveBeenCalledWith('team-1', 'skill-1')
    })
  })

  it('uploads a tar.gz skill archive', async () => {
    vi.mocked(teamSkillApi.uploadTeamSkill).mockResolvedValue(mockSkills[0])
    const user = userEvent.setup()
    const file = new File(['archive'], 'lint.tar.gz', { type: 'application/gzip' })

    renderWithProviders(<TeamSkillsPanel />)
    await waitFor(() => expect(screen.getByText('lint')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'Upload Skill' }))
    const dialog = screen.getByRole('dialog')
    await user.upload(within(dialog).getByLabelText('Skill archive'), file)
    await user.click(within(dialog).getByRole('button', { name: 'Upload Skill' }))

    await waitFor(() => {
      expect(teamSkillApi.uploadTeamSkill).toHaveBeenCalledWith('team-1', file)
    })
  })

  it('renders an empty state', async () => {
    vi.mocked(teamSkillApi.listTeamSkills).mockResolvedValue([])

    renderWithProviders(<TeamSkillsPanel />)

    await waitFor(() => expect(screen.getByText('No team skills')).toBeInTheDocument())
  })
})
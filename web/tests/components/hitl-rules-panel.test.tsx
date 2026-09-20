import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { HitlRuleDto } from '@/types/hitl-rule-types'

import { HitlRulesPanel } from '@/components/settings/hitl-rules-panel'
import * as hitlRuleApi from '@/lib/hitl-rule-api'
import { useAuthStore } from '@/store/auth-store'

vi.mock('@/lib/hitl-rule-api', () => ({
  createHitlRule: vi.fn(),
  deleteHitlRule: vi.fn(),
  listHitlRules: vi.fn(),
}))

const mockRules: HitlRuleDto[] = [
  {
    action: 'ALLOW',
    commandRoot: 'npm test',
    createdAt: '2026-09-06T12:00:00Z',
    createdByName: 'Alice',
    createdByUserId: 'user-1',
    id: 'rule-1',
    ruleType: 'EXACT',
    teamId: 'team-1',
  },
  {
    action: 'DENY',
    commandRoot: 'rm -rf',
    createdAt: '2026-09-06T13:00:00Z',
    createdByName: 'Bob',
    createdByUserId: 'user-2',
    id: 'rule-2',
    ruleType: 'PREFIX_WILD',
    teamId: 'team-1',
  },
]

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>,
  )
}

describe('HitlRulesPanel', () => {
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
  })

  it('renders HITL rules list', async () => {
    vi.mocked(hitlRuleApi.listHitlRules).mockResolvedValue(mockRules)

    renderWithProviders(<HitlRulesPanel />)

    await waitFor(() => {
      expect(screen.getByText('HITL Rules')).toBeInTheDocument()
      const table = screen.getByRole('table')
      expect(within(table).getByText('npm test')).toBeInTheDocument()
      expect(within(table).getByText('rm -rf')).toBeInTheDocument()
      expect(within(table).getByText('ALLOW')).toBeInTheDocument()
      expect(within(table).getByText('DENY')).toBeInTheDocument()
      expect(within(table).getByText('Exact')).toBeInTheDocument()
      expect(within(table).getByText('Prefix Wildcard')).toBeInTheDocument()
      expect(within(table).getByText('Alice')).toBeInTheDocument()
      expect(within(table).getByText('Bob')).toBeInTheDocument()
    })
  })

  it('renders a mobile card layout alongside the table', async () => {
    vi.mocked(hitlRuleApi.listHitlRules).mockResolvedValue(mockRules)

    renderWithProviders(<HitlRulesPanel />)

    await waitFor(() => {
      expect(screen.getByRole('table')).toBeInTheDocument()
    })

    const cards = screen.getByTestId('hitl-rule-cards')
    expect(within(cards).getByText('npm test')).toBeInTheDocument()
    expect(within(cards).getByText('ALLOW')).toBeInTheDocument()
    expect(within(cards).getByText('Exact')).toBeInTheDocument()
    expect(
      within(cards).getByRole('button', { name: 'Delete rule for npm test' }),
    ).toBeInTheDocument()
  })

  it('filters rules by search input', async () => {
    vi.mocked(hitlRuleApi.listHitlRules).mockResolvedValue(mockRules)
    const user = userEvent.setup()

    renderWithProviders(<HitlRulesPanel />)

    await waitFor(() => {
      expect(screen.getAllByText('npm test').length).toBeGreaterThan(0)
    })

    const searchInput = screen.getByPlaceholderText('Search commands...')
    await user.type(searchInput, 'npm')

    expect(screen.getAllByText('npm test').length).toBeGreaterThan(0)
    expect(screen.queryByText('rm -rf')).not.toBeInTheDocument()
  })

  it('filters rules by action filter', async () => {
    vi.mocked(hitlRuleApi.listHitlRules).mockResolvedValue(mockRules)
    const user = userEvent.setup()

    renderWithProviders(<HitlRulesPanel />)

    await waitFor(() => {
      expect(screen.getAllByText('npm test').length).toBeGreaterThan(0)
    })

    await user.click(screen.getByRole('button', { name: /^Deny$/i }))

    expect(screen.queryByText('npm test')).not.toBeInTheDocument()
    expect(screen.getAllByText('rm -rf').length).toBeGreaterThan(0)

    await user.click(screen.getByRole('button', { name: /^Allow$/i }))
    expect(screen.getAllByText('npm test').length).toBeGreaterThan(0)
    expect(screen.queryByText('rm -rf')).not.toBeInTheDocument()
  })

  it('opens delete confirmation modal and calls delete API', async () => {
    vi.mocked(hitlRuleApi.listHitlRules).mockResolvedValue(mockRules)
    vi.mocked(hitlRuleApi.deleteHitlRule).mockResolvedValue(undefined)
    const user = userEvent.setup()

    renderWithProviders(<HitlRulesPanel />)

    await waitFor(() => {
      expect(screen.getAllByText('npm test').length).toBeGreaterThan(0)
    })

    const deleteBtn = within(screen.getByRole('table')).getByRole('button', {
      name: 'Delete rule for npm test',
    })
    await user.click(deleteBtn)

    await waitFor(() => {
      expect(screen.getByText('Delete HITL Rule')).toBeInTheDocument()
      expect(screen.getByText(/Are you sure you want to delete the HITL rule for/)).toBeInTheDocument()
    })

    const confirmBtn = screen.getByRole('button', { name: 'Delete Rule' })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(hitlRuleApi.deleteHitlRule).toHaveBeenCalledWith('team-1', 'rule-1')
    })
  })

  it('wraps long command patterns instead of overflowing the table', async () => {
    const longHitlRule: HitlRuleDto = {
      action: 'ALLOW',
      commandRoot:
        '/usr/local/bin/a-very-long-command-with-no-spaces-that-would-overflow-the-table',
      createdAt: '2026-09-06T12:00:00Z',
      createdByName: 'Alice',
      createdByUserId: 'user-1',
      id: 'rule-long',
      ruleType: 'PREFIX_WILD',
      teamId: 'team-1',
    }
    vi.mocked(hitlRuleApi.listHitlRules).mockResolvedValue([longHitlRule])

    renderWithProviders(<HitlRulesPanel />)

    const commandCells = await screen.findAllByText(longHitlRule.commandRoot)
    expect(commandCells.length).toBeGreaterThan(0)
    commandCells.forEach((cell) => expect(cell).toHaveClass('break-all'))
  })

  it('displays empty state when no rules exist', async () => {
    vi.mocked(hitlRuleApi.listHitlRules).mockResolvedValue([])

    renderWithProviders(<HitlRulesPanel />)

    await waitFor(() => {
      expect(screen.getByText('No HITL rules configured')).toBeInTheDocument()
    })
  })
})

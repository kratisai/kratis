import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ExecutionEnvironmentDto } from '@/lib/environment-api'

import { EnvironmentList } from '@/components/settings/environment-list'
import { useEnvironments } from '@/hooks/use-environments'

vi.mock('@/hooks/use-environments', () => ({
  useCreateConnector: vi.fn(() => ({ isPending: false, mutate: vi.fn() })),
  useDeleteEnvironment: vi.fn(() => ({ mutate: vi.fn() })),
  useEnvironments: vi.fn(),
  useTerminateEnvironment: vi.fn(() => ({ mutate: vi.fn() })),
}))

describe('EnvironmentList Component', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders the "Last Activity" column header in the desktop table', () => {
    vi.mocked(useEnvironments).mockReturnValue({
      data: [
        {
          containerId: null,
          createdAt: '2026-01-01T00:00:00Z',
          id: 'env-1',
          lastActivity: null,
          lastHeartbeat: null,
          name: 'Sandbox 1',
          status: 'DISCONNECTED',
          teamId: 'team-1',
          type: 'SANDBOX',
          updatedAt: '2026-01-01T00:00:00Z',
        },
      ],
      isLoading: false,
    } as unknown as ReturnType<typeof useEnvironments>)

    render(<EnvironmentList isOwner={true} />)

    expect(screen.getByRole('columnheader', { name: /last activity/i })).toBeInTheDocument()
  })

  it('renders environments sorted by latest activity descending and null activity at the bottom', () => {
    const mockEnvironments: ExecutionEnvironmentDto[] = [
      {
        containerId: null,
        createdAt: '2026-01-01T00:00:00Z',
        id: 'env-inactive',
        lastActivity: null,
        lastHeartbeat: null,
        name: 'Inactive Sandbox',
        status: 'DISCONNECTED',
        teamId: 'team-1',
        type: 'SANDBOX',
        updatedAt: '2026-01-01T00:00:00Z',
      },
      {
        containerId: null,
        createdAt: '2026-01-01T00:00:00Z',
        id: 'env-older',
        lastActivity: '2026-09-20T12:00:00Z',
        lastHeartbeat: null,
        name: 'Older Connector',
        status: 'CONNECTED',
        teamId: 'team-1',
        type: 'CONNECTOR',
        updatedAt: '2026-01-01T00:00:00Z',
      },
      {
        containerId: null,
        createdAt: '2026-01-01T00:00:00Z',
        id: 'env-recent',
        lastActivity: '2026-09-27T10:00:00Z',
        lastHeartbeat: null,
        name: 'Recent Connector',
        status: 'CONNECTED',
        teamId: 'team-1',
        type: 'CONNECTOR',
        updatedAt: '2026-01-01T00:00:00Z',
      },
    ]

    vi.mocked(useEnvironments).mockReturnValue({
      data: mockEnvironments,
      isLoading: false,
    } as unknown as ReturnType<typeof useEnvironments>)

    render(<EnvironmentList isOwner={true} />)

    // Check desktop table rows order
    const table = screen.getByRole('table')
    const rows = within(table).getAllByRole('row').slice(1) // skip header row

    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByText('Recent Connector')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Older Connector')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Inactive Sandbox')).toBeInTheDocument()

    // Assert that inactive environment displays '-' for Last Activity
    const dashes = within(rows[2]).getAllByText('-')
    expect(dashes.length).toBeGreaterThanOrEqual(1)

    // Check mobile card order
    const mobileCards = screen.getByTestId('environment-cards')
    const cardHeadings = within(mobileCards)
      .getAllByText(/Connector|Sandbox/)
      .map((el) => el.textContent)

    expect(cardHeadings[0]).toBe('Recent Connector')
    expect(cardHeadings[1]).toBe('Older Connector')
    expect(cardHeadings[2]).toBe('Inactive Sandbox')
  })
})

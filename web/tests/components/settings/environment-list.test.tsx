import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ExecutionEnvironmentDto } from '@/lib/environment-api'

import { EnvironmentList } from '@/components/settings/environment-list'
import { useDeleteEnvironment, useEnvironments, useResumeEnvironment } from '@/hooks/use-environments'
import { useHaltExecution } from '@/hooks/use-executions'

vi.mock('@/hooks/use-environments', () => ({
  useCreateConnector: vi.fn(() => ({ isPending: false, mutate: vi.fn() })),
  useDeleteEnvironment: vi.fn(() => ({ mutate: vi.fn() })),
  useEnvironments: vi.fn(),
  useResumeEnvironment: vi.fn(() => ({ mutate: vi.fn() })),
}))

vi.mock('@/hooks/use-executions', () => ({
  useHaltExecution: vi.fn(() => ({ isPending: false, mutate: vi.fn() })),
}))

describe('EnvironmentList Component', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  function makeEnv(
    id: string,
    name: string,
    type: string,
    status: string,
  ): ExecutionEnvironmentDto {
    return {
      createdAt: '2026-01-01T00:00:00Z',
      id,
      lastActivity: null,
      lastHeartbeat: null,
      name,
      status,
      teamId: 'team-1',
      type,
      updatedAt: '2026-01-01T00:00:00Z',
    }
  }

  function mockEnvironmentsData(data: ExecutionEnvironmentDto[]) {
    vi.mocked(useEnvironments).mockReturnValue({
      data,
      isLoading: false,
    } as unknown as ReturnType<typeof useEnvironments>)
  }

  it('renders the "Last Activity" column header in the desktop table', () => {
    vi.mocked(useEnvironments).mockReturnValue({
      data: [
        {
          createdAt: '2026-01-01T00:00:00Z',
          id: 'env-1',
          lastActivity: null,
          lastHeartbeat: null,
          name: 'Sandbox 1',
          status: 'PENDING_CONNECT',
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
        createdAt: '2026-01-01T00:00:00Z',
        id: 'env-inactive',
        lastActivity: null,
        lastHeartbeat: null,
        name: 'Never Connected Sandbox',
        status: 'PENDING_CONNECT',
        teamId: 'team-1',
        type: 'SANDBOX',
        updatedAt: '2026-01-01T00:00:00Z',
      },
      {
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
    expect(within(rows[2]).getByText('Never Connected Sandbox')).toBeInTheDocument()

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
    expect(cardHeadings[2]).toBe('Never Connected Sandbox')
  })

  it('renders Sleeping and Terminated badges', () => {
    const mockEnvironments: ExecutionEnvironmentDto[] = [
      makeEnv('sleeping-env', 'Sleeping Sandbox', 'SANDBOX', 'SLEEPING'),
      makeEnv('terminated-env', 'Terminated Sandbox', 'SANDBOX', 'TERMINATED'),
    ]
    mockEnvironmentsData(mockEnvironments)

    render(<EnvironmentList isOwner={true} />)

    expect(screen.getAllByText('Sleeping').length).toBeGreaterThanOrEqual(1)
    const terminatedBadges = screen.getAllByText('Terminated')
    expect(terminatedBadges.length).toBeGreaterThanOrEqual(1)
    terminatedBadges.forEach((badge) => {
      expect(badge).toHaveClass('text-destructive')
    })
  })

  it('renders every status as an outline badge coloured by state', () => {
    mockEnvironmentsData([
      makeEnv('active-env', 'Active Sandbox', 'SANDBOX', 'CONNECTED'),
      makeEnv('connecting-env', 'Connecting Sandbox', 'SANDBOX', 'PENDING_CONNECT'),
      makeEnv('sleeping-env', 'Sleeping Sandbox', 'SANDBOX', 'SLEEPING'),
    ])

    render(<EnvironmentList isOwner={true} />)

    const table = screen.getByRole('table')
    const badgeFor = (label: string) => within(table).getByText(label)

    expect(badgeFor('Active')).toHaveClass('border-green-600', 'text-green-600')
    expect(badgeFor('Connecting')).not.toHaveClass('text-destructive', 'text-green-600')
    expect(badgeFor('Sleeping')).toHaveClass('border-amber-500', 'text-amber-600')
    expect(within(table).queryByText('Disconnected')).not.toBeInTheDocument()
  })

  it('shows connection state only for CONNECTED environments with an active execution', () => {
    mockEnvironmentsData([
      {
        ...makeEnv('live-env', 'Live Sandbox', 'SANDBOX', 'CONNECTED'),
        activeChatId: 'chat-1',
        activeExecutionId: 'exec-1',
        connected: true,
      },
      {
        ...makeEnv('stale-env', 'Stale Sandbox', 'SANDBOX', 'CONNECTED'),
        activeChatId: 'chat-2',
        activeExecutionId: 'exec-2',
        connected: false,
      },
      {
        ...makeEnv('idle-env', 'Idle Sandbox', 'SANDBOX', 'CONNECTED'),
        connected: true,
      },
      {
        ...makeEnv('sleeping-env', 'Sleeping Sandbox', 'SANDBOX', 'SLEEPING'),
        activeChatId: 'chat-3',
        activeExecutionId: 'exec-3',
        connected: false,
      },
      {
        ...makeEnv('terminated-env', 'Terminated Sandbox', 'SANDBOX', 'TERMINATED'),
        activeChatId: 'chat-4',
        activeExecutionId: 'exec-4',
        connected: false,
      },
    ])

    render(<EnvironmentList isOwner={true} />)

    const table = screen.getByRole('table')
    const rows = within(table).getAllByRole('row').slice(1)
    const rowFor = (name: string) => rows.find((row) => within(row).queryByText(name)) as HTMLElement

    expect(within(rowFor('Live Sandbox')).getByText('Connected')).toBeInTheDocument()
    expect(within(rowFor('Stale Sandbox')).getByText('Disconnected')).toBeInTheDocument()
    expect(within(rowFor('Idle Sandbox')).queryByText(/Connected|Disconnected/)).not.toBeInTheDocument()
    expect(within(rowFor('Sleeping Sandbox')).queryByText(/Connected|Disconnected/)).not.toBeInTheDocument()
    expect(within(rowFor('Terminated Sandbox')).queryByText(/Connected|Disconnected/)).not.toBeInTheDocument()
  })

  it('shows connection state in mobile cards', () => {
    mockEnvironmentsData([
      {
        ...makeEnv('stale-env', 'Stale Sandbox', 'SANDBOX', 'CONNECTED'),
        activeChatId: 'chat-2',
        activeExecutionId: 'exec-2',
        connected: false,
      },
    ])

    render(<EnvironmentList isOwner={true} />)

    const mobileCards = screen.getByTestId('environment-cards')
    expect(within(mobileCards).getByText('Active')).toBeInTheDocument()
    expect(within(mobileCards).getByText('Disconnected')).toBeInTheDocument()
  })

  it('offers Resume for a sleeping sandbox only', () => {
    const mockEnvironments: ExecutionEnvironmentDto[] = [
      makeEnv('running-env', 'Running Sandbox', 'SANDBOX', 'CONNECTED'),
      makeEnv('sleeping-env', 'Sleeping Sandbox', 'SANDBOX', 'SLEEPING'),
    ]
    mockEnvironmentsData(mockEnvironments)

    const resumeMutate = vi.fn()
    vi.mocked(useResumeEnvironment).mockReturnValue({ mutate: resumeMutate } as never)

    render(<EnvironmentList isOwner={true} />)

    expect(
      screen.queryByRole('button', { name: `Sleep Running Sandbox` }),
    ).not.toBeInTheDocument()

    fireEvent.click(screen.getAllByRole('button', { name: 'Resume Sleeping Sandbox' })[0])
    expect(resumeMutate).toHaveBeenCalledWith('sleeping-env')
  })

  it('offers Delete and no Resume for a terminated sandbox', () => {
    const deleteMutate = vi.fn()
    vi.mocked(useDeleteEnvironment).mockReturnValue({ mutate: deleteMutate } as never)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    mockEnvironmentsData([makeEnv('terminated-env', 'Terminated Sandbox', 'SANDBOX', 'TERMINATED')])

    render(<EnvironmentList isOwner={true} />)

    expect(screen.queryByRole('button', { name: /Resume/ })).not.toBeInTheDocument()

    const table = screen.getByRole('table')
    fireEvent.click(within(table).getByRole('button', { name: 'Delete Terminated Sandbox' }))
    expect(deleteMutate).toHaveBeenCalledWith('terminated-env')

    const mobileCards = screen.getByTestId('environment-cards')
    expect(
      within(mobileCards).getByRole('button', { name: 'Delete Terminated Sandbox' }),
    ).toBeInTheDocument()
  })

  it('hides Delete for active, connecting and sleeping sandboxes', () => {
    mockEnvironmentsData([
      makeEnv('running-env', 'Running Sandbox', 'SANDBOX', 'CONNECTED'),
      makeEnv('sleeping-env', 'Sleeping Sandbox', 'SANDBOX', 'SLEEPING'),
      makeEnv('connecting-env', 'Connecting Sandbox', 'SANDBOX', 'PENDING_CONNECT'),
    ])

    render(<EnvironmentList isOwner={true} />)

    expect(screen.queryByRole('button', { name: /^Delete / })).not.toBeInTheDocument()
  })

  it('offers Delete for an offline connector with no active execution only', () => {
    mockEnvironmentsData([
      { ...makeEnv('offline-env', 'Offline Connector', 'CONNECTOR', 'CONNECTED'), connected: false },
      { ...makeEnv('live-env', 'Live Connector', 'CONNECTOR', 'CONNECTED'), connected: true },
      {
        ...makeEnv('busy-env', 'Busy Connector', 'CONNECTOR', 'CONNECTED'),
        activeChatId: 'chat-1',
        activeExecutionId: 'exec-1',
        connected: false,
      },
    ])

    render(<EnvironmentList isOwner={true} />)

    const table = screen.getByRole('table')
    expect(within(table).getByRole('button', { name: 'Delete Offline Connector' })).toBeInTheDocument()
    expect(within(table).queryByRole('button', { name: 'Delete Live Connector' })).not.toBeInTheDocument()
    expect(within(table).queryByRole('button', { name: 'Delete Busy Connector' })).not.toBeInTheDocument()
  })

  it('halts an environment through the execution halt API', () => {
    const haltMutate = vi.fn()
    vi.mocked(useHaltExecution).mockReturnValue({ isPending: false, mutate: haltMutate } as never)
    mockEnvironmentsData([
      {
        ...makeEnv('env-sandbox', 'Sleeping Sandbox', 'SANDBOX', 'SLEEPING'),
        activeChatId: 'chat-1',
        activeExecutionId: 'exec-1',
      },
    ])
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    render(<EnvironmentList isOwner={true} />)

    fireEvent.click(screen.getAllByRole('button', { name: 'Halt Sleeping Sandbox' })[0])
    expect(haltMutate).toHaveBeenCalledWith({ chatId: 'chat-1', executionId: 'exec-1' })
  })

  it('offers no halt action when an environment has no active execution', () => {
    mockEnvironmentsData([makeEnv('env-idle', 'Idle Sandbox', 'SANDBOX', 'PENDING_CONNECT')])

    render(<EnvironmentList isOwner={true} />)

    expect(screen.queryByRole('button', { name: /Halt/ })).not.toBeInTheDocument()
  })
})

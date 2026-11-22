import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { UsageLogEntry } from '@/types/usage-types'

import { UsageView } from '@/components/views/usage-view'

vi.mock('@tanstack/react-router', async () => {
  const actual = await vi.importActual('@tanstack/react-router')
  return {
    ...actual,
    Link: ({ children, to, ...props }: React.ComponentProps<'a'> & { to?: string }) => (
      <a href={to} {...props}>
        {children}
      </a>
    ),
  }
})

const entryTemplate: UsageLogEntry = {
  activityTitle: 'Test Agent Task',
  agentName: 'OpenCode',
  durationSeconds: 5,
  id: 'exec-1',
  models: [
    {
      litellmAlias: null,
      modelIdentifier: 'gpt-4o',
      modelKind: 'CHAT',
      totalSpend: 0.03,
      totalTokens: 1200,
    },
  ],
  status: 'COMPLETED',
  targetUrl: '/chats/chat-1/executions/exec-1',
  timestamp: new Date().toISOString(),
  usageType: 'EXECUTION',
  userEmail: 'user@kratis.ai',
}

let logEntries: UsageLogEntry[]

const fetchUsageLogsMock = vi.hoisted(() => vi.fn())
vi.mock('@/lib/usage-api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/usage-api')>('@/lib/usage-api')
  return { ...actual, fetchUsageLogs: fetchUsageLogsMock }
})

vi.mock('@/hooks/use-usage', () => ({
  useUsageLogs: (
    teamId: null | string,
    params?: {
      agent?: string
      model?: string
      page?: number
      status?: string
      timeframe?: string
      usageType?: string
      userEmail?: string
    },
  ) => {
    fetchUsageLogsMock(teamId, params)
    return {
      data: {
        content: logEntries,
        number: params?.page ?? 0,
        size: 15,
        totalElements: logEntries.length,
        totalPages: 3,
      },
      isLoading: false,
      refetch: vi.fn(),
    }
  },
  useUsageSummary: () => ({
    data: {
      agentShareByCost: [
        { cost: 1.2345, count: 42, label: 'OpenCode', percentage: 100.0, tokens: 15000 },
      ],
      agentShareByTime: [
        { cost: 10, count: 42, label: 'OpenCode', percentage: 100.0, tokens: 15000 },
      ],
      modelShare: [{ cost: 1.0, count: 30, label: 'gpt-4o', percentage: 80.0, tokens: 12000 }],
      timeSeries: [{ cost: 0.5, timestamp: new Date().toISOString(), tokens: 5000 }],
      totalCost: 1.2345,
      totalOperations: 42,
      totalTokens: 15000,
    },
    isLoading: false,
    refetch: vi.fn(),
  }),
}))

vi.mock('@/store/auth-store', () => ({
  useAuthStore: Object.assign(
    vi.fn((selector: (state: { currentTeamId: string }) => unknown) =>
      selector({ currentTeamId: 'team-1' }),
    ),
    {
      getState: vi.fn(() => ({ accessToken: 'mock-token' })),
    },
  ),
}))

describe('UsageView', () => {
  beforeEach(() => {
    logEntries = [entryTemplate]
    fetchUsageLogsMock.mockClear()
  })

  it('renders usage dashboard summary cards and table entries', () => {
    render(<UsageView />)

    expect(screen.getByText('Usage & Analytics')).toBeDefined()
    expect(screen.getByText('Total Cost')).toBeDefined()
    expect(screen.getByText('$1.2345')).toBeDefined()
    expect(screen.getByText('Total Tokens Used')).toBeDefined()
    expect(screen.getByText('15,000')).toBeDefined()
    expect(screen.getByText('Test Agent Task')).toBeDefined()
  })

  it('renders the activity title as a link to the underlying execution when targetUrl is present', () => {
    render(<UsageView />)

    expect(screen.getByRole('link', { name: 'Test Agent Task' })).toHaveAttribute(
      'href',
      '/chats/chat-1/executions/exec-1',
    )
  })

  it('renders the activity title as plain text when targetUrl is missing', () => {
    logEntries = [{ ...entryTemplate, targetUrl: null }]
    render(<UsageView />)

    expect(screen.getByText('Test Agent Task').closest('a')).toBeNull()
  })

  it('renders every model behind a multi-model ingestion entry', () => {
    render(<UsageView />)

    expect(screen.getAllByText('gpt-4o').length).toBeGreaterThan(0)
  })

  it('renders chat and embedding rows that share one identifier without duplicate keys', () => {
    logEntries = [
      {
        ...entryTemplate,
        id: 'ingest-2',
        models: [
          {
            litellmAlias: null,
            modelIdentifier: 'shared-model',
            modelKind: 'CHAT',
            totalSpend: 0.01,
            totalTokens: 100,
          },
          {
            litellmAlias: null,
            modelIdentifier: 'shared-model',
            modelKind: 'EMBEDDING',
            totalSpend: 0.01,
            totalTokens: 100,
          },
        ],
        usageType: 'INGESTION',
      },
    ]
    render(<UsageView />)

    // Both (kind, identifier) pairs render; the composite key keeps them distinct.
    expect(screen.getAllByText('shared-model')).toHaveLength(2)
    expect(screen.getByText('200 tk')).toBeDefined()
    expect(screen.getByText('$0.0200')).toBeDefined()
  })

  it('formats the log table duration as human-readable time, not raw seconds', () => {
    render(<UsageView />)

    expect(screen.getByText('5s')).toBeDefined()
  })

  it('shows durations on the Time share tab instead of dollar amounts', async () => {
    const user = userEvent.setup()
    render(<UsageView />)

    await user.click(screen.getByRole('tab', { name: 'Time' }))

    expect(screen.getByText('10s')).toBeDefined()
  })

  it('renders a usage-type badge for each entry', () => {
    logEntries = [
      entryTemplate,
      { ...entryTemplate, activityTitle: 'Wiki Build', id: 'tw-1', usageType: 'TEAM_WIKI' },
      {
        ...entryTemplate,
        id: 'unknown-type-1',
        // Double assertion marks the seed as deliberately out-of-union wire data.
        usageType: 'FUTURE_TYPE' as unknown as UsageLogEntry['usageType'],
      },
    ]
    render(<UsageView />)

    expect(screen.getByRole('button', { name: 'OpenCode execution' })).toBeDefined()
    expect(screen.getByRole('button', { name: 'Team Wiki' })).toBeDefined()
    // A usage type the web layer does not know yet falls back to the raw value
    // instead of crashing the table render.
    expect(screen.getByText('FUTURE_TYPE')).toBeDefined()
  })

  it('does not render a status badge for a chat row because sessions have no live status', () => {
    logEntries = [
      {
        ...entryTemplate,
        activityTitle: 'Ask Kratis about onboarding',
        agentName: null,
        id: 'chat-session-1',
        status: 'RUNNING',
        targetUrl: null,
        usageType: 'CHAT',
      },
    ]
    render(<UsageView />)

    expect(screen.getByText('Chat Session')).toBeDefined()
    expect(screen.queryByText('Active')).toBeNull()
    expect(screen.queryByText('Complete')).toBeNull()
    expect(document.querySelector('.animate-spin')).toBeNull()
  })

  it('renders an unrecognized status without crashing', () => {
    logEntries = [
      {
        ...entryTemplate,
        // Double assertion marks the seed as deliberately out-of-union wire data.
        status: 'CANCELLED' as unknown as UsageLogEntry['status'],
      },
    ]
    render(<UsageView />)

    expect(screen.getByText('CANCELLED')).toBeDefined()
  })

  it('renders a settled IDLE execution as complete instead of as a running operation', () => {
    logEntries = [{ ...entryTemplate, status: 'IDLE' }]
    render(<UsageView />)

    expect(screen.getByText('Complete')).toBeDefined()
    expect(screen.queryByText('IDLE')).toBeNull()
    expect(document.querySelector('.animate-spin')).toBeNull()
  })

  it('renders an active RUNNING execution with a spinner and friendly label', () => {
    logEntries = [{ ...entryTemplate, status: 'RUNNING' }]
    render(<UsageView />)

    expect(screen.getByText('Active')).toBeDefined()
    expect(screen.queryByText('RUNNING')).toBeNull()
    expect(document.querySelector('.animate-spin')).not.toBeNull()
  })

  it('sends the selected usage type as a query param and the raw all value at the hook boundary', async () => {
    const user = userEvent.setup()
    render(<UsageView />)

    // The usage-type select is the second combobox (after the timeframe select).
    const usageTypeSelect = screen.getAllByRole('combobox')[1]
    expect(fetchUsageLogsMock).toHaveBeenCalledWith(
      'team-1',
      expect.objectContaining({ usageType: 'all' }),
    )

    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ page: 1 }),
    )

    await user.click(usageTypeSelect)
    await user.click(screen.getByRole('option', { name: 'Team Wiki' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ page: 0, usageType: 'TEAM_WIKI' }),
    )

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await user.click(screen.getAllByRole('combobox')[1])
    await user.click(screen.getByRole('option', { name: 'Executions' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ page: 0, usageType: 'EXECUTION' }),
    )

    await user.click(screen.getAllByRole('combobox')[1])
    await user.click(screen.getByRole('option', { name: 'All Types' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ page: 0, usageType: 'all' }),
    )
  })

  it('adds user, model, usage-type, and harness filters from the activity table', async () => {
    const user = userEvent.setup()
    render(<UsageView />)

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await user.click(screen.getByRole('button', { name: 'Filter by user user@kratis.ai' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ page: 0, userEmail: 'user@kratis.ai' }),
    )

    await user.click(screen.getByRole('button', { name: 'Filter by model gpt-4o' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ model: 'gpt-4o', page: 0, userEmail: 'user@kratis.ai' }),
    )

    await user.click(screen.getByRole('button', { name: 'OpenCode execution' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({
        agent: 'OpenCode',
        model: 'gpt-4o',
        page: 0,
        usageType: 'EXECUTION',
        userEmail: 'user@kratis.ai',
      }),
    )

    await user.click(screen.getByRole('button', { name: 'OpenCode execution' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({
        agent: undefined,
        model: 'gpt-4o',
        page: 0,
        usageType: 'all',
        userEmail: 'user@kratis.ai',
      }),
    )
  })

  it('filters by chat and ingestion from their usage-type badges and toggles them off', async () => {
    const user = userEvent.setup()
    logEntries = [
      entryTemplate,
      {
        ...entryTemplate,
        activityTitle: 'Ask Kratis about onboarding',
        agentName: null,
        id: 'chat-1',
        usageType: 'CHAT',
      },
      {
        ...entryTemplate,
        activityTitle: 'Repository ingestion',
        agentName: null,
        id: 'ingest-1',
        usageType: 'INGESTION',
      },
    ]
    render(<UsageView />)

    await user.click(screen.getByRole('button', { name: 'OpenCode execution' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ agent: 'OpenCode', usageType: 'EXECUTION' }),
    )

    await user.click(screen.getByRole('button', { name: 'Chat Session' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ page: 0, usageType: 'CHAT' }),
    )
    expect(fetchUsageLogsMock.mock.lastCall?.[1]).toHaveProperty('agent', undefined)

    await user.click(screen.getByRole('button', { name: 'Chat Session' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ page: 0, usageType: 'all' }),
    )

    await user.click(screen.getByRole('button', { name: 'Repository Ingestion' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ page: 0, usageType: 'INGESTION' }),
    )
  })

  it('toggles user, model, and status filters off when clicked again', async () => {
    const user = userEvent.setup()
    render(<UsageView />)

    const userButton = screen.getAllByRole('button', { name: 'Filter by user user@kratis.ai' })[0]
    await user.click(userButton)
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ userEmail: 'user@kratis.ai' }),
    )
    await user.click(userButton)
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ userEmail: undefined }),
    )

    const modelButton = screen.getByRole('button', { name: 'Filter by model gpt-4o' })
    await user.click(modelButton)
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ model: 'gpt-4o' }),
    )
    await user.click(modelButton)
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ model: undefined }),
    )

    const statusButton = screen.getAllByRole('button', { name: 'Complete' })[0]
    await user.click(statusButton)
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ status: 'COMPLETED' }),
    )
    await user.click(statusButton)
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ status: undefined }),
    )
  })

  it('filters by status from the status dropdown', async () => {
    const user = userEvent.setup()
    render(<UsageView />)

    await user.click(screen.getAllByRole('combobox')[2])
    await user.click(screen.getByRole('option', { name: 'Completed' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ page: 0, status: 'COMPLETED' }),
    )

    await user.click(screen.getAllByRole('combobox')[2])
    await user.click(screen.getByRole('option', { name: 'All Statuses' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ page: 0, status: undefined }),
    )
  })

  it('omits the usageType query param for the all value when fetching', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      json: async () => ({ content: [], number: 0, size: 15, totalElements: 0, totalPages: 0 }),
      ok: true,
    })
    const originalFetch = globalThis.fetch
    globalThis.fetch = mockFetch
    try {
      const { fetchUsageLogs } = await vi.importActual<typeof import('@/lib/usage-api')>(
        '@/lib/usage-api',
      )

      await fetchUsageLogs('team-1', { usageType: 'all' })
      expect(mockFetch).toHaveBeenCalledWith(
        expect.stringContaining('/api/v1/teams/team-1/usage-logs?'),
        expect.anything(),
      )
      const urlWithoutType = mockFetch.mock.calls[0][0] as string
      expect(urlWithoutType).not.toContain('usageType')

      await fetchUsageLogs('team-1', {
        status: 'COMPLETED',
        usageType: 'TEAM_WIKI',
        userEmail: 'user@kratis.ai',
      })
      const urlWithType = mockFetch.mock.calls[1][0] as string
      expect(urlWithType).toContain('usageType=TEAM_WIKI')
      expect(urlWithType).toContain('userEmail=user%40kratis.ai')
      expect(urlWithType).toContain('status=COMPLETED')
    } finally {
      globalThis.fetch = originalFetch
    }
  })
})

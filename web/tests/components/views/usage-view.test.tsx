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
  totalSpend: 0.03,
  totalTokens: 1200,
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
  useUsageLogs: (teamId: null | string, params?: { usageType?: string }) => {
    fetchUsageLogsMock(teamId, params)
    return {
      data: {
        content: logEntries,
        number: 0,
        size: 15,
        totalElements: logEntries.length,
        totalPages: 1,
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

    expect(screen.getByText('Execution')).toBeDefined()
    expect(screen.getByText('Team Wiki')).toBeDefined()
    // A usage type the web layer does not know yet falls back to the raw value
    // instead of crashing the table render.
    expect(screen.getByText('FUTURE_TYPE')).toBeDefined()
  })

  it('renders a status badge for a chat row derived from the session', () => {
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
    expect(screen.getByText('RUNNING')).toBeDefined()
    expect(document.querySelector('.animate-spin')).not.toBeNull()
  })

  it('renders a COMPLETED status badge for an ended chat session', () => {
    logEntries = [
      {
        ...entryTemplate,
        id: 'chat-session-2',
        status: 'COMPLETED',
        usageType: 'CHAT',
      },
    ]
    render(<UsageView />)

    expect(screen.getByText('COMPLETED')).toBeDefined()
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

    await user.click(usageTypeSelect)
    await user.click(screen.getByRole('option', { name: 'Team Wiki' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ usageType: 'TEAM_WIKI' }),
    )

    await user.click(screen.getAllByRole('combobox')[1])
    await user.click(screen.getByRole('option', { name: 'All Types' }))
    expect(fetchUsageLogsMock).toHaveBeenLastCalledWith(
      'team-1',
      expect.objectContaining({ usageType: 'all' }),
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

      await fetchUsageLogs('team-1', { usageType: 'TEAM_WIKI' })
      const urlWithType = mockFetch.mock.calls[1][0] as string
      expect(urlWithType).toContain('usageType=TEAM_WIKI')
    } finally {
      globalThis.fetch = originalFetch
    }
  })
})

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
  modelIdentifiers: ['gpt-4o'],
  status: 'COMPLETED',
  targetUrl: '/chats/chat-1/executions/exec-1',
  timestamp: new Date().toISOString(),
  totalSpend: 0.03,
  totalTokens: 1200,
  usageType: 'EXECUTION',
  userEmail: 'user@kratis.ai',
}

let logEntries: UsageLogEntry[]

vi.mock('@/hooks/use-usage', () => ({
  useUsageLogs: () => ({
    data: {
      content: logEntries,
      number: 0,
      size: 15,
      totalElements: logEntries.length,
      totalPages: 1,
    },
    isLoading: false,
    refetch: vi.fn(),
  }),
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
  useAuthStore: (selector: (state: { currentTeamId: string }) => unknown) =>
    selector({ currentTeamId: 'team-1' }),
}))

describe('UsageView', () => {
  beforeEach(() => {
    logEntries = [entryTemplate]
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
})

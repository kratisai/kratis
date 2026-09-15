import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { UsageView } from '@/components/views/usage-view'

vi.mock('@/hooks/use-usage', () => ({
  useUsageLogs: () => ({
    data: {
      content: [
        {
          activityTitle: 'Test Agent Task',
          agentName: 'OpenCode',
          durationSeconds: 5,
          id: 'exec-1',
          modelIdentifiers: ['gpt-4o'],
          status: 'COMPLETED',
          timestamp: new Date().toISOString(),
          totalSpend: 0.03,
          totalTokens: 1200,
          usageType: 'EXECUTION',
          userEmail: 'user@kratis.ai',
        },
      ],
      number: 0,
      size: 15,
      totalElements: 1,
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
  it('renders usage dashboard summary cards and table entries', () => {
    render(<UsageView />)

    expect(screen.getByText('Usage & Analytics')).toBeDefined()
    expect(screen.getByText('Total Cost')).toBeDefined()
    expect(screen.getByText('$1.2345')).toBeDefined()
    expect(screen.getByText('Total Tokens Used')).toBeDefined()
    expect(screen.getByText('15,000')).toBeDefined()
    expect(screen.getByText('Test Agent Task')).toBeDefined()
  })

  it('renders every model behind a multi-model ingestion entry', () => {
    render(<UsageView />)

    expect(screen.getAllByText('gpt-4o').length).toBeGreaterThan(0)
  })
})

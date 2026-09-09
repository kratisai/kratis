import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { SandboxExecutionDto } from '@/lib/execution-api'

import { ExecutionUsageSummary } from '@/components/session/execution-usage-summary'

const { executionsMock } = vi.hoisted(() => ({ executionsMock: vi.fn() }))

vi.mock('@/hooks/use-executions', () => ({
  useChatExecutions: () => ({ data: executionsMock() }),
}))

function executionDto(overrides: Partial<SandboxExecutionDto>): SandboxExecutionDto {
  return {
    chatId: 'chat-1',
    completedAt: null,
    completionTokens: null,
    exitCode: null,
    id: 'exec-1',
    promptTokens: null,
    startedAt: '2026-01-01T00:00:00.000Z',
    status: 'COMPLETED',
    totalSpend: null,
    totalTokens: null,
    usageLastUpdatedAt: null,
    ...overrides,
  }
}

describe('ExecutionUsageSummary', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    executionsMock.mockReturnValue([])
  })

  it('renders nothing when no usage exists for the execution', () => {
    executionsMock.mockReturnValue([executionDto({})])
    const { container } = render(<ExecutionUsageSummary chatId="chat-1" executionId="exec-1" />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders cost and tokens from the execution record', () => {
    executionsMock.mockReturnValue([
      executionDto({ completionTokens: 400, promptTokens: 600, totalSpend: 0.42, totalTokens: 1000 }),
    ])

    render(<ExecutionUsageSummary chatId="chat-1" executionId="exec-1" />)

    expect(screen.getByTestId('execution-usage-summary')).toBeTruthy()
    expect(screen.getByText('$0.42')).toBeTruthy()
    expect(screen.getByText('1,000')).toBeTruthy()
  })

  it('renders dashes for missing values', () => {
    executionsMock.mockReturnValue([
      executionDto({ completionTokens: 400, promptTokens: 600, totalSpend: null, totalTokens: 1000 }),
    ])

    render(<ExecutionUsageSummary chatId="chat-1" executionId="exec-1" />)

    expect(screen.getByText('—')).toBeTruthy()
    expect(screen.getByText('1,000')).toBeTruthy()
  })

  it('reads usage for the requested execution only', () => {
    executionsMock.mockReturnValue([
      executionDto({ completionTokens: 400, promptTokens: 600, totalSpend: 0.42, totalTokens: 1000 }),
    ])

    const { container } = render(<ExecutionUsageSummary chatId="chat-1" executionId="exec-2" />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing when the execution list has not loaded', () => {
    const { container } = render(<ExecutionUsageSummary chatId="chat-1" executionId="exec-1" />)
    expect(container).toBeEmptyDOMElement()
  })
})

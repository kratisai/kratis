import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ChatUsageSummary } from '@/components/chat/chat-usage-summary'

const { usageMock } = vi.hoisted(() => ({ usageMock: vi.fn() }))

vi.mock('@/hooks/use-chat-usage', () => ({
  useChatUsage: () => ({ data: usageMock() }),
}))

describe('ChatUsageSummary', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    usageMock.mockReturnValue(undefined)
  })

  it('renders nothing when no aggregate totals exist', () => {
    usageMock.mockReturnValue({
      completionTokens: null,
      promptTokens: null,
      totalSpend: null,
      totalTokens: null,
      usageLastUpdatedAt: null,
    })
    const { container } = render(<ChatUsageSummary chatId="chat-1" />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders totals with detailed tooltip', () => {
    usageMock.mockReturnValue({
      completionTokens: 400,
      promptTokens: 600,
      totalSpend: 0.42,
      totalTokens: 1000,
      usageLastUpdatedAt: '2026-11-24T20:00:00.000Z',
    })
    render(<ChatUsageSummary chatId="chat-1" />)

    expect(screen.getByTestId('chat-usage-summary')).toBeTruthy()
    expect(screen.getByText('$0.42')).toBeTruthy()
    expect(screen.getByText('1,000')).toBeTruthy()
    expect(screen.getByTestId('chat-usage-summary').getAttribute('title')).toContain('Prompt tokens: 600')
    expect(screen.getByTestId('chat-usage-summary').getAttribute('title')).toContain('Completion tokens: 400')
    expect(screen.getByTestId('chat-usage-summary').getAttribute('title')).toContain('Updated')
  })

  it('shows sub-cent costs to four decimal places', () => {
    usageMock.mockReturnValue({
      completionTokens: null,
      promptTokens: null,
      totalSpend: 0.0012,
      totalTokens: null,
      usageLastUpdatedAt: null,
    })
    render(<ChatUsageSummary chatId="chat-1" />)
    expect(screen.getByText('$0.0012')).toBeTruthy()
    expect(screen.getByText('Tokens:').parentElement).toHaveTextContent('—')
  })
})

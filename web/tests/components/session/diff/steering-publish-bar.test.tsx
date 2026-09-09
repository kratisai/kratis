import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SteeringPublishBar } from '@/components/session/diff/steering-publish-bar'
import * as diffApi from '@/lib/diff-api'
import { useDiffReviewStore } from '@/store/diff-review-store'

describe('SteeringPublishBar', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    useDiffReviewStore.setState({
      activeCommentBox: null,
      draftComments: {},
    })
  })

  it('renders unfocused idle bar with pending comments count', () => {
    useDiffReviewStore.setState({
      draftComments: {
        'exec-1': [
          { comment: 'Fix test', createdAt: Date.now(), id: 'c1', line: 10, path: 'test.ts' },
          { comment: 'Update docs', createdAt: Date.now(), id: 'c2', line: 20, path: 'docs.md' },
        ],
      },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <SteeringPublishBar chatId="chat-1" executionId="exec-1" executionStatus="RUNNING" />
      </QueryClientProvider>,
    )

    expect(screen.getByText(/2 review comments pending/i)).toBeInTheDocument()

    const bar = screen.getByTestId('steering-publish-bar')
    expect(bar).toHaveClass('fixed', 'md:absolute')
  })

  it('switches to focused state on click and dispatches steering with comments', async () => {
    const steerSpy = vi.spyOn(diffApi, 'steerExecution').mockResolvedValue()

    useDiffReviewStore.setState({
      draftComments: {
        'exec-1': [
          { comment: 'Fix null check', createdAt: Date.now(), id: 'c1', line: 12, path: 'app.ts' },
        ],
      },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <SteeringPublishBar chatId="chat-1" executionId="exec-1" executionStatus="RUNNING" />
      </QueryClientProvider>,
    )

    const idleBar = screen.getByText(/1 review comment pending/i)
    fireEvent.click(idleBar)

    const textarea = screen.getByLabelText('Steering guidance input')
    fireEvent.change(textarea, { target: { value: 'Please address these comments' } })

    const sendBtn = screen.getByRole('button', { name: /Send Feedback \(1\)/i })
    fireEvent.click(sendBtn)

    await waitFor(() => {
      expect(steerSpy).toHaveBeenCalledWith('chat-1', 'exec-1', {
        comments: [{ codeSnippet: undefined, comment: 'Fix null check', line: 12, path: 'app.ts' }],
        prompt: 'Please address these comments',
      })
    })
  })

  it('shows Publish button when execution status is COMPLETED', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <SteeringPublishBar chatId="chat-1" executionId="exec-1" executionStatus="COMPLETED" />
      </QueryClientProvider>,
    )

    const idleBar = screen.getByText(/Tap to steer agent/i)
    fireEvent.click(idleBar)

    expect(screen.getByRole('button', { name: /Publish/i })).toBeInTheDocument()
  })

  it('shows Publish button when execution status is IDLE', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <SteeringPublishBar chatId="chat-1" executionId="exec-1" executionStatus="IDLE" />
      </QueryClientProvider>,
    )

    // Publish button should be present in the idle bar
    expect(screen.getByRole('button', { name: /Publish/i })).toBeInTheDocument()

    // And also in the expanded guidance box
    const idleBar = screen.getByText(/Tap to steer agent/i)
    fireEvent.click(idleBar)

    expect(screen.getByRole('button', { name: /Publish/i })).toBeInTheDocument()
  })

  it('hides Publish button when execution status is RUNNING', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <SteeringPublishBar chatId="chat-1" executionId="exec-1" executionStatus="RUNNING" />
      </QueryClientProvider>,
    )

    expect(screen.queryByRole('button', { name: /Publish/i })).not.toBeInTheDocument()
  })
})

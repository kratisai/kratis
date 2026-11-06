import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SteeringBar } from '@/components/session/diff/steering-bar'
import * as diffApi from '@/lib/diff-api'
import * as publishApi from '@/lib/publish-api'
import { useDiffReviewStore } from '@/store/diff-review-store'

describe('SteeringBar', () => {
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
        <SteeringBar chatId="chat-1" executionId="exec-1" executionStatus="RUNNING" />
      </QueryClientProvider>,
    )

    expect(screen.getByText(/2 review comments pending/i)).toBeInTheDocument()

    const bar = screen.getByTestId('steering-bar')
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
        <SteeringBar chatId="chat-1" executionId="exec-1" executionStatus="RUNNING" />
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

  it('offers a publish shortcut on small screens once the change set is settled', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <SteeringBar chatId="chat-1" executionId="exec-1" executionStatus="COMPLETED" />
      </QueryClientProvider>,
    )

    expect(screen.getAllByRole('button', { name: /publish/i })).not.toHaveLength(0)
    expect(screen.getAllByRole('button', { name: /publish/i })[0]).toHaveClass('hidden', 'md:inline-flex')
  })

  it('hides the publish shortcut while a turn is still running', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <SteeringBar chatId="chat-1" executionId="exec-1" executionStatus="RUNNING" />
      </QueryClientProvider>,
    )

    expect(screen.queryByRole('button', { name: /publish/i })).not.toBeInTheDocument()
  })

  it('opens the publish dialog from the mobile publish shortcut', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockReturnValue(new Promise(() => {}))

    render(
      <QueryClientProvider client={queryClient}>
        <SteeringBar chatId="chat-1" executionId="exec-1" executionStatus="COMPLETED" />
      </QueryClientProvider>,
    )

    fireEvent.click(screen.getAllByRole('button', { name: /publish/i })[0])

    expect(await screen.findByText('Publish Changes')).toBeInTheDocument()
  })

  it('allows steering when execution status is FAILED (recovery)', async () => {
    const steerSpy = vi.spyOn(diffApi, 'steerExecution').mockResolvedValue()

    render(
      <QueryClientProvider client={queryClient}>
        <SteeringBar chatId="chat-1" executionId="exec-1" executionStatus="FAILED" />
      </QueryClientProvider>,
    )

    const idleBar = screen.getByText(/Tap to steer agent/i)
    fireEvent.click(idleBar)

    const textarea = screen.getByLabelText('Steering guidance input')
    fireEvent.change(textarea, { target: { value: 'Recover from the context overflow' } })

    const sendBtn = screen.getByRole('button', { name: /Send Guidance/i })
    expect(sendBtn).toBeEnabled()
    fireEvent.click(sendBtn)

    await waitFor(() => {
      expect(steerSpy).toHaveBeenCalledWith('chat-1', 'exec-1', {
        comments: [],
        prompt: 'Recover from the context overflow',
      })
    })
  })

  it('renders nothing while the environment is asleep', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <SteeringBar
          chatId="chat-1"
          environmentStatus="SLEEPING"
          executionId="exec-1"
          executionStatus="FAILED"
        />
      </QueryClientProvider>,
    )

    expect(screen.queryByTestId('steering-bar')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Steering guidance input')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /publish/i })).not.toBeInTheDocument()
  })
})

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ExecutionStageView } from '@/components/topic/execution-stage-view'
import { useChatExecutions } from '@/hooks/use-executions'
import * as diffApi from '@/lib/diff-api'
import * as execApi from '@/lib/execution-api'
import { useActivityStore } from '@/store/activity-store'
import { useDiffReviewStore } from '@/store/diff-review-store'
import { useExecutionStore } from '@/store/execution-store'

let mockSearch: { tab?: string } = { tab: undefined }

vi.mock('@tanstack/react-router', () => ({
  useSearch: () => mockSearch,
}))

vi.mock('@/components/session/execution-activity-log', () => ({
  ExecutionActivityLog: () => <div data-testid="activity-log-mock">Mock Activity Log</div>,
}))

vi.mock('@/components/session/diff/execution-diff-tab', () => ({
  ExecutionDiffTab: () => <div data-testid="diff-tab-mock">Mock Diff Tab</div>,
}))

vi.mock('@/hooks/use-executions', () => ({
  useChatExecutions: vi.fn(() => ({
    data: [],
    refetch: vi.fn(),
  })),
}))

vi.mock('@/lib/activity-log-export', () => ({
  downloadActivityLog: vi.fn(),
}))

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

function completedExecution(): execApi.SandboxExecutionDto {
  return {
    chatId: 'session-1',
    completedAt: '2026-01-02T00:00:00.000Z',
    exitCode: 0,
    harness: 'OPENCODE',
    id: 'exec-1',
    startedAt: '2026-01-01T00:00:00.000Z',
    status: 'COMPLETED',
  }
}

describe('ExecutionStageView', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    mockSearch = { tab: undefined }
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    vi.mocked(useChatExecutions).mockReturnValue({
      data: [],
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useChatExecutions>)
    useActivityStore.setState({ activitiesByExecution: {} })
    useExecutionStore.setState({
      logs: {},
      replayingExecutionId: null,
      terminalFullscreen: false,
      terminalHeight: 256,
      terminalOpen: false,
    })
    vi.spyOn(diffApi, 'fetchDiffSummary').mockResolvedValue({
      baseCommit: 'commit-base',
      files: [
        {
          additions: 10,
          deletions: 2,
          isCollapsedByDefault: false,
          path: 'src/app.ts',
          status: 'MODIFIED',
        },
      ],
      headCommit: 'commit-head',
      totalAdditions: 10,
      totalDeletions: 2,
    })
  })

  function renderView(chatId = 'session-1', executionId = 'exec-1') {
    return render(
      <QueryClientProvider client={queryClient}>
        <ExecutionStageView chatId={chatId} executionId={executionId} />
      </QueryClientProvider>,
    )
  }

  it('renders activity log by default with steering bar', async () => {
    renderView()

    expect(await screen.findByTestId('activity-log-mock')).toBeInTheDocument()
    expect(screen.getByTestId('steering-publish-bar')).toBeInTheDocument()
  })

  it('renders the diff tab when the changes sub-tab is selected in the URL', async () => {
    mockSearch = { tab: 'changes' }
    useDiffReviewStore.setState({ diffViewMode: 'split' })

    renderView()

    const diffTab = await screen.findByTestId('diff-tab-mock')
    expect(diffTab).toBeInTheDocument()
    expect(screen.queryByTestId('activity-log-mock')).not.toBeInTheDocument()

    // The changes branch is width-constrained at every flex level so wide diff
    // tables scroll internally instead of stretching the page (or the file cards)
    const diffPane = diffTab.parentElement
    expect(diffPane).not.toBeNull()
    expect(diffPane).toHaveClass('min-w-0')
    expect(diffPane?.parentElement).toHaveClass('min-w-0')
  })

  it('does not duplicate the status indicator in the actions row', () => {
    vi.mocked(useChatExecutions).mockReturnValue({
      data: [{ ...completedExecution(), completedAt: null, status: 'RUNNING' }],
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useChatExecutions>)

    renderView()

    // Status is shown on the stage-nav run card; the actions row must not repeat it
    expect(screen.queryByText('Active')).not.toBeInTheDocument()
  })

  it('does not duplicate the harness badge in the actions row', () => {
    vi.mocked(useChatExecutions).mockReturnValue({
      data: [{ ...completedExecution(), harness: 'CLAUDE_CODE' }],
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useChatExecutions>)

    renderView()

    // Harness is shown on the stage-nav run card; the actions row must not repeat it
    expect(screen.queryByText('Claude Code')).not.toBeInTheDocument()
  })

  it('shows the usage summary (cost and tokens) in the actions row', () => {
    vi.mocked(useChatExecutions).mockReturnValue({
      data: [
        {
          ...completedExecution(),
          totalSpend: 0.42,
          totalTokens: 1234,
        },
      ],
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useChatExecutions>)

    renderView()

    expect(screen.getByTestId('execution-usage-summary')).toBeInTheDocument()
    expect(screen.getByText('Tokens:')).toBeInTheDocument()
  })

  it('replays a completed execution whose log is not yet populated', async () => {
    vi.mocked(useChatExecutions).mockReturnValue({
      data: [completedExecution()],
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useChatExecutions>)
    const replaySpy = vi.spyOn(useExecutionStore.getState(), 'replayActivities')

    renderView()

    await waitFor(() => {
      expect(replaySpy).toHaveBeenCalledWith('exec-1')
    })
    expect(useExecutionStore.getState().replayingExecutionId).toBe('exec-1')
    replaySpy.mockRestore()
  })

  it('does not replay a running execution', async () => {
    vi.mocked(useChatExecutions).mockReturnValue({
      data: [{ ...completedExecution(), completedAt: null, status: 'RUNNING' }],
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useChatExecutions>)
    const replaySpy = vi.spyOn(useExecutionStore.getState(), 'replayActivities')

    renderView()

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(replaySpy).not.toHaveBeenCalled()
    replaySpy.mockRestore()
  })

  it('toggles the terminal from the options menu when an execution is running', async () => {
    const user = userEvent.setup()
    vi.mocked(useChatExecutions).mockReturnValue({
      data: [{ ...completedExecution(), completedAt: null, status: 'RUNNING' }],
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useChatExecutions>)
    useExecutionStore.setState({ terminalOpen: false })

    renderView()

    await user.click(screen.getByRole('button', { name: /activity log options/i }))
    await user.click(screen.getByRole('menuitem', { name: /show terminal/i }))
    expect(useExecutionStore.getState().terminalOpen).toBe(true)

    await user.click(screen.getByRole('button', { name: /activity log options/i }))
    await user.click(screen.getByRole('menuitem', { name: /hide terminal/i }))
    expect(useExecutionStore.getState().terminalOpen).toBe(false)
  })

  it('terminates the execution via the execution-scoped endpoint from the options menu', async () => {
    const user = userEvent.setup()
    const terminateMock = vi.spyOn(execApi, 'terminateExecution').mockResolvedValue(undefined)
    vi.mocked(useChatExecutions).mockReturnValue({
      data: [{ ...completedExecution(), completedAt: null, status: 'RUNNING' }],
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useChatExecutions>)
    useExecutionStore.setState({ terminalOpen: false })

    renderView()

    await user.click(screen.getByRole('button', { name: /activity log options/i }))
    await user.click(screen.getByRole('menuitem', { name: /terminate/i }))

    await waitFor(() => {
      expect(terminateMock).toHaveBeenCalledWith('session-1', 'exec-1')
    })
    expect(useExecutionStore.getState().logs['exec-1']).toBeUndefined()
  })

  it('hides terminal and terminate options when there is no active execution', async () => {
    const user = userEvent.setup()

    renderView()

    await user.click(screen.getByRole('button', { name: /activity log options/i }))
    expect(screen.getByRole('menuitem', { name: /download/i })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /show terminal/i })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: /terminate/i })).toBeNull()
  })

  it('allows content to flow into the mobile document and keeps the desktop bounded layout', () => {
    renderView()

    const root = screen.getByTestId('execution-stage-view')
    expect(root).toHaveClass('min-w-0', 'md:overflow-hidden')
    expect(root).not.toHaveClass('overflow-hidden')

    const bar = screen.getByTestId('steering-publish-bar')
    expect(bar).toHaveClass('fixed', 'md:absolute')
  })
})

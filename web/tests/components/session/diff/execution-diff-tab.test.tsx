import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ExecutionDiffTab } from '@/components/session/diff/execution-diff-tab'
import * as diffApi from '@/lib/diff-api'
import { useDiffReviewStore } from '@/store/diff-review-store'

describe('ExecutionDiffTab', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    useDiffReviewStore.setState({
      collapsedFiles: {},
      draftComments: {},
    })
  })

  it('renders loading state while diff summary is fetching', () => {
    vi.spyOn(diffApi, 'fetchDiffSummary').mockReturnValue(new Promise(() => {}))

    render(
      <QueryClientProvider client={queryClient}>
        <ExecutionDiffTab chatId="chat-1" executionId="exec-1" />
      </QueryClientProvider>,
    )

    expect(screen.getByText(/Inspecting workspace changes.../i)).toBeInTheDocument()
  })

  it('renders empty state when there are 0 changed files', async () => {
    vi.spyOn(diffApi, 'fetchDiffSummary').mockResolvedValue({
      baseCommit: 'commit-a',
      files: [],
      headCommit: 'commit-b',
      totalAdditions: 0,
      totalDeletions: 0,
    })

    render(
      <QueryClientProvider client={queryClient}>
        <ExecutionDiffTab chatId="chat-1" executionId="exec-1" />
      </QueryClientProvider>,
    )

    expect(await screen.findByText(/No file changes in working tree yet/i)).toBeInTheDocument()
  })

  it('renders file summary headers and expands/collapses all files', async () => {
    vi.spyOn(diffApi, 'fetchDiffSummary').mockResolvedValue({
      baseCommit: 'commit-a',
      files: [
        {
          additions: 10,
          deletions: 2,
          isCollapsedByDefault: false,
          path: 'src/A.ts',
          status: 'MODIFIED',
        },
        {
          additions: 5,
          deletions: 0,
          isCollapsedByDefault: false,
          path: 'src/B.ts',
          status: 'ADDED',
        },
      ],
      headCommit: 'commit-b',
      totalAdditions: 15,
      totalDeletions: 2,
    })

    render(
      <QueryClientProvider client={queryClient}>
        <ExecutionDiffTab chatId="chat-1" executionId="exec-1" />
      </QueryClientProvider>,
    )

    expect(await screen.findByText(/2 files/i)).toBeInTheDocument()
    expect(screen.getByText('+15')).toBeInTheDocument()
    expect(screen.getByText('A.ts')).toBeInTheDocument()
    expect(screen.getByText('B.ts')).toBeInTheDocument()

    const collapseAllBtn = screen.getByRole('button', { name: /Collapse All/i })
    fireEvent.click(collapseAllBtn)

    expect(useDiffReviewStore.getState().collapsedFiles['src/A.ts']).toBe(true)
    expect(useDiffReviewStore.getState().collapsedFiles['src/B.ts']).toBe(true)

    const expandAllBtn = screen.getByRole('button', { name: /Expand All/i })
    fireEvent.click(expandAllBtn)

    expect(useDiffReviewStore.getState().collapsedFiles['src/A.ts']).toBe(false)
    expect(useDiffReviewStore.getState().collapsedFiles['src/B.ts']).toBe(false)
  })

  it('sorts files alphabetically regardless of input order', async () => {
    vi.spyOn(diffApi, 'fetchDiffSummary').mockResolvedValue({
      baseCommit: 'commit-a',
      files: [
        {
          additions: 1,
          deletions: 0,
          isCollapsedByDefault: false,
          path: 'src/zebra.ts',
          status: 'ADDED',
        },
        {
          additions: 2,
          deletions: 0,
          isCollapsedByDefault: false,
          path: 'src/alpha.ts',
          status: 'ADDED',
        },
        {
          additions: 3,
          deletions: 0,
          isCollapsedByDefault: false,
          path: 'src/middle.ts',
          status: 'ADDED',
        },
      ],
      headCommit: 'commit-b',
      totalAdditions: 6,
      totalDeletions: 0,
    })

    render(
      <QueryClientProvider client={queryClient}>
        <ExecutionDiffTab chatId="chat-1" executionId="exec-1" />
      </QueryClientProvider>,
    )

    expect(await screen.findByText(/3 files/i)).toBeInTheDocument()

    const renderedFiles = screen
      .getAllByTestId(/^diff-file-/)
      .map((el) => el.getAttribute('data-testid'))

    expect(renderedFiles).toEqual([
      'diff-file-src/alpha.ts',
      'diff-file-src/middle.ts',
      'diff-file-src/zebra.ts',
    ])
  })
})

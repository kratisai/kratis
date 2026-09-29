import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { DiffFileCard } from '@/components/session/diff/diff-file-card'
import * as diffApi from '@/lib/diff-api'
import { useDiffReviewStore } from '@/store/diff-review-store'

describe('DiffFileCard', () => {
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

  it('renders file header with addition/deletion stats', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <DiffFileCard
          chatId="chat-1"
          executionId="exec-1"
          file={{
            additions: 12,
            deletions: 3,
            isCollapsedByDefault: false,
            path: 'src/config.ts',
            status: 'MODIFIED',
          }}
        />
      </QueryClientProvider>,
    )

    expect(screen.getByText('config.ts')).toBeInTheDocument()
    expect(screen.getByText('src')).toBeInTheDocument()
    expect(screen.getByText('+12')).toBeInTheDocument()
    expect(screen.getByText('-3')).toBeInTheDocument()
  })

  it('toggles collapse on header click', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <DiffFileCard
          chatId="chat-1"
          executionId="exec-1"
          file={{
            additions: 5,
            deletions: 0,
            isCollapsedByDefault: true,
            path: 'src/new-file.ts',
            status: 'ADDED',
          }}
        />
      </QueryClientProvider>,
    )

    const header = screen.getByText('new-file.ts')
    // Initially collapsed -> clicking expands it
    fireEvent.click(header)
    expect(useDiffReviewStore.getState().collapsedFiles['src/new-file.ts']).toBe(false)

    // Clicking again collapses it
    fireEvent.click(header)
    expect(useDiffReviewStore.getState().collapsedFiles['src/new-file.ts']).toBe(true)
  })

  it('fetches and displays file diff when expanded', async () => {
    useDiffReviewStore.setState({
      collapsedFiles: { 'src/index.ts': false },
    })

    vi.spyOn(diffApi, 'fetchFileDiff').mockResolvedValue({
      additions: 2,
      deletions: 1,
      patch: `@@ -1,2 +1,3 @@\n-old\n+new\n+added`,
      path: 'src/index.ts',
      totalLines: 10,
    })

    render(
      <QueryClientProvider client={queryClient}>
        <DiffFileCard
          chatId="chat-1"
          executionId="exec-1"
          file={{
            additions: 2,
            deletions: 1,
            isCollapsedByDefault: true,
            path: 'src/index.ts',
            status: 'MODIFIED',
          }}
        />
      </QueryClientProvider>,
    )

    const viewer = await screen.findByTestId('diff-hunk-viewer')
    expect(viewer).toBeInTheDocument()
    expect(screen.getByText('index.ts')).toBeInTheDocument()
  })
})

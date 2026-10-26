import type { ComponentProps } from 'react'

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { PublishCapabilities } from '@/types/diff-types'

import { PublishModal } from '@/components/session/diff/publish-modal'
import { ApiError } from '@/lib/auth-api'
import * as diffApi from '@/lib/diff-api'
import * as publishApi from '@/lib/publish-api'

let queryClient: QueryClient

vi.mock('@/hooks/use-credentials', () => ({
  useCredentials: vi.fn(() => ({
    data: [
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'cred-new',
        name: 'Replacement token',
        type: 'PAT',
      },
    ],
  })),
}))

beforeEach(() => {
  if (typeof Element.prototype.hasPointerCapture !== 'function') {
    Element.prototype.hasPointerCapture = () => false
  }
  if (typeof Element.prototype.scrollIntoView !== 'function') {
    Element.prototype.scrollIntoView = () => {}
  }
})

function makeCapabilities(overrides: Partial<PublishCapabilities> = {}): PublishCapabilities {
  return {
    canCreateRepository: false,
    changeSet: {
      additions: 20,
      deletions: 5,
      digest: 'digest-1',
      files: [{ additions: 20, deletions: 5, path: 'src/App.tsx', status: 'modified' }],
    },
    commitCount: 2,
    defaultBaseBranch: 'main',
    digest: 'digest-total-1',
    hasUncommittedChanges: false,
    incomingCommits: 0,
    newRepo: false,
    repositoryType: 'GITHUB',
    stats: {
      additions: 20,
      commitsAhead: 2,
      deletions: 5,
      formattedSummary: '2 commits (+20, -5 lines)',
      hasChanges: true,
      stagedFiles: 0,
      unstagedFiles: 0,
    },
    status: 'UP_TO_DATE',
    suggestedBody: 'Summary of changes.',
    suggestedTitle: 'feat: new feature',
    supportsPullRequests: true,
    target: {
      branchName: 'kratis/feature-9b3c2a3f',
      kind: 'OPEN_PR',
    },
    visibilityOptions: [],
    ...overrides,
  }
}

function modalTree(props: Partial<ComponentProps<typeof PublishModal>> = {}) {
  return (
    <QueryClientProvider client={queryClient}>
      <PublishModal
        chatId="chat-123"
        executionId="9b3c2a3f-55b7-47a7-83ac-6b9c592f2a07"
        onOpenChange={vi.fn()}
        open={true}
        {...props}
      />
    </QueryClientProvider>
  )
}

function renderModal(props: Partial<ComponentProps<typeof PublishModal>> = {}) {
  return render(modalTree(props))
}

describe('PublishModal', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
  })

  it('renders capabilities loading state initially', () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockReturnValue(new Promise(() => {}))

    renderModal()

    expect(
      screen.getByText(/checking repository status and preparing publish details/i),
    ).toBeInTheDocument()
  })

  it('renders the publish target without an up-to-date banner', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(makeCapabilities())

    renderModal()

    await waitFor(() => {
      expect(screen.getByText(/opens a new pr/i)).toBeInTheDocument()
    })

    expect(screen.queryByText(/up to date with main/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create pull request/i })).toBeInTheDocument()
  })

  it('shows nothing to publish when the target branch already has all changes', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(
      makeCapabilities({
        changeSet: { additions: 0, deletions: 0, digest: 'digest-empty', files: [] },
        commitCount: 0,
        stats: {
          additions: 0,
          commitsAhead: 0,
          deletions: 0,
          formattedSummary: 'There are no changes to publish',
          hasChanges: false,
          stagedFiles: 0,
          unstagedFiles: 0,
        },
        target: { branchName: 'kratis/feature-9b3c2a3f', kind: 'UPDATE_PR', prNumber: 42 },
      }),
    )

    renderModal()

    await waitFor(() => {
      expect(screen.getByText(/there are no changes to publish/i)).toBeInTheDocument()
    })

    expect(screen.queryByRole('button', { name: /update pr/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/updates pr #42/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/branch name/i)).not.toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /close/i }).length).toBeGreaterThan(0)
  })

  it('clears stale changes after a publish and reopen', async () => {
    const noChanges = makeCapabilities({
      changeSet: { additions: 0, deletions: 0, digest: 'digest-empty', files: [] },
      commitCount: 0,
      stats: {
        additions: 0,
        commitsAhead: 0,
        deletions: 0,
        formattedSummary: 'There are no changes to publish',
        hasChanges: false,
        stagedFiles: 0,
        unstagedFiles: 0,
      },
    })
    let resolveRefresh!: (capabilities: PublishCapabilities) => void
    const refresh = new Promise<PublishCapabilities>((resolve) => {
      resolveRefresh = resolve
    })
    vi.spyOn(publishApi, 'fetchPublishCapabilities')
      .mockResolvedValueOnce(makeCapabilities())
      .mockReturnValueOnce(refresh)
    vi.spyOn(publishApi, 'publishPullRequest').mockResolvedValue({
      baseBranch: 'main',
      headBranch: 'kratis/feature-9b3c2a3f',
      prNumber: 42,
      prUrl: 'https://github.com/org/repo/pull/42',
    })

    const { rerender } = renderModal()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /create pull request/i })).toBeInTheDocument()
    })
    fireEvent.click(screen.getByRole('button', { name: /create pull request/i }))

    await waitFor(() => {
      expect(screen.getByText(/pull request created successfully/i)).toBeInTheDocument()
    })

    rerender(modalTree({ open: false }))
    rerender(modalTree({ open: true }))

    await waitFor(() => {
      expect(screen.getByText(/there are no changes to publish/i)).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: /create pull request/i })).not.toBeInTheDocument()

    await act(async () => {
      resolveRefresh(noChanges)
    })

    expect(screen.getByText(/there are no changes to publish/i)).toBeInTheDocument()
  })

  it('renders rebase required and enables rebase action', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(
      makeCapabilities({
        incomingCommits: 3,
        status: 'REBASE_REQUIRED',
      }),
    )
    const rebaseSpy = vi.spyOn(publishApi, 'rebaseExecution').mockResolvedValue(makeCapabilities())

    renderModal()

    await waitFor(() => {
      expect(screen.getByText(/main has 3 new commits\. rebase required before publish\./i)).toBeInTheDocument()
    })

    const rebaseBtns = screen.getAllByRole('button', { name: /rebase onto main/i })
    expect(rebaseBtns.length).toBeGreaterThan(0)

    fireEvent.click(rebaseBtns[0])

    await waitFor(() => {
      expect(rebaseSpy).toHaveBeenCalledWith('chat-123', '9b3c2a3f-55b7-47a7-83ac-6b9c592f2a07')
    })
  })

  it('shows a refresh state after rebase and enables publish when the state arrives', async () => {
    let resolveRefresh!: (capabilities: PublishCapabilities) => void
    const refreshed = new Promise<PublishCapabilities>((resolve) => {
      resolveRefresh = resolve
    })
    vi.spyOn(publishApi, 'fetchPublishCapabilities')
      .mockResolvedValueOnce(makeCapabilities({ incomingCommits: 3, status: 'REBASE_REQUIRED' }))
      .mockReturnValueOnce(refreshed)
    vi.spyOn(publishApi, 'rebaseExecution').mockResolvedValue(makeCapabilities())

    renderModal()

    await waitFor(() => {
      expect(screen.getByText(/rebase required before publish/i)).toBeInTheDocument()
    })

    fireEvent.click(screen.getAllByRole('button', { name: /rebase onto main/i })[0])

    await waitFor(() => {
      expect(
        screen.getByText(/rebase complete\. refreshing repository status/i),
      ).toBeInTheDocument()
    })

    expect(screen.queryByText(/rebase required before publish/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /create pull request/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /refreshing status/i })).toBeDisabled()

    await act(async () => {
      resolveRefresh(makeCapabilities())
    })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /create pull request/i })).toBeInTheDocument()
    })

    expect(
      screen.queryByText(/rebase complete\. refreshing repository status/i),
    ).not.toBeInTheDocument()
  })

  it('creates pull request when publish button is clicked', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(makeCapabilities())
    const publishSpy = vi.spyOn(publishApi, 'publishPullRequest').mockResolvedValue({
      baseBranch: 'main',
      headBranch: 'kratis/feature-9b3c2a3f',
      prNumber: 42,
      prUrl: 'https://github.com/org/repo/pull/42',
    })

    renderModal()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /create pull request/i })).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /create pull request/i }))

    await waitFor(() => {
      expect(publishSpy).toHaveBeenCalledWith(
        'chat-123',
        '9b3c2a3f-55b7-47a7-83ac-6b9c592f2a07',
        expect.objectContaining({
          branchName: 'kratis/feature-9b3c2a3f',
          digest: 'digest-total-1',
          squash: true,
          title: 'feat: new feature',
        }),
      )
    })
  })

  it('renders update target when target is UPDATE_PR', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(
      makeCapabilities({
        target: {
          branchName: 'kratis/feature-9b3c2a3f',
          kind: 'UPDATE_PR',
          prNumber: 42,
        },
      }),
    )

    renderModal()

    await waitFor(() => {
      expect(screen.getByText(/updates pr #42/i)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /update pr/i })).toBeInTheDocument()
    })
  })

  it('renders error and handles rebase conflict escalation', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(makeCapabilities())
    vi.spyOn(publishApi, 'publishPullRequest').mockRejectedValue(
      new ApiError('conflict on target branch', 409),
    )
    const steerSpy = vi.spyOn(diffApi, 'steerExecution').mockResolvedValue()

    renderModal({ executionStatus: 'IDLE' })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /create pull request/i })).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /create pull request/i }))

    await waitFor(() => {
      expect(screen.getByText(/conflict on target branch/i)).toBeInTheDocument()
    })

    const escalateBtn = screen.getByRole('button', { name: /ask agent to rebase & retest/i })
    expect(escalateBtn).toBeInTheDocument()

    fireEvent.click(escalateBtn)

    await waitFor(() => {
      expect(steerSpy).toHaveBeenCalled()
    })
  })
})

import type { ComponentProps } from 'react'

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { PublishCapabilities } from '@/types/diff-types'

import { PublishModal } from '@/components/session/diff/publish-modal'
import { ApiError } from '@/lib/auth-api'
import * as diffApi from '@/lib/diff-api'
import * as publishApi from '@/lib/publish-api'

let queryClient: QueryClient

function makeCapabilities(overrides: Partial<PublishCapabilities> = {}): PublishCapabilities {
  return {
    defaultBaseBranch: 'main',
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
    suggestedBody: 'Summary of changes.',
    suggestedTitle: 'feat: new feature',
    supportsPullRequests: true,
    ...overrides,
  }
}

function renderModal(props: Partial<ComponentProps<typeof PublishModal>> = {}) {
  return render(
    <QueryClientProvider client={queryClient}>
      <PublishModal
        chatId="chat-123"
        executionId="9b3c2a3f-55b7-47a7-83ac-6b9c592f2a07"
        onOpenChange={vi.fn()}
        open={true}
        {...props}
      />
    </QueryClientProvider>,
  )
}

describe('PublishModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
  })

  it('renders capabilities loading state initially', () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockReturnValue(
      new Promise(() => {}), // Never resolves
    )

    renderModal()

    expect(
      screen.getByText(/checking repository status and preparing publish details/i),
    ).toBeInTheDocument()
  })

  it('renders the PR creation form with plan sentence, editable branch, and prefilled message', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(
      makeCapabilities({
        stats: {
          additions: 345,
          commitsAhead: 3,
          deletions: 96,
          formattedSummary: '3 commits (+345, -96 lines)',
          hasChanges: true,
          stagedFiles: 0,
          unstagedFiles: 0,
        },
        suggestedBody: 'Summary of changes across 3 commits.',
        suggestedTitle: 'feat(auth): implement token validation',
      }),
    )

    renderModal()

    await waitFor(() => {
      expect(screen.getByText('3 commits (+345, -96 lines)')).toBeInTheDocument()
    })

    const plan = screen.getByTestId('publish-plan')
    expect(plan).toHaveTextContent('opens a pull request against main')

    const branchInput = screen.getByLabelText(/feature branch/i)
    expect(branchInput).toHaveValue('kratis/feature-9b3c2a3f')
    expect(branchInput).toBeEnabled()

    expect(screen.getByLabelText(/commit message/i)).toHaveValue(
      'feat(auth): implement token validation',
    )
    expect(screen.getByLabelText(/description \/ summary/i)).toHaveValue(
      'Summary of changes across 3 commits.',
    )
    expect(screen.getByLabelText(/create pull request as draft/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/squash 3 commits into a single commit/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create pull request/i })).toBeInTheDocument()

    expect(screen.queryByLabelText(/base branch/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/target branch/i)).not.toBeInTheDocument()
  })

  it('renders zero changes banner and disables submit button when hasChanges is false', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(
      makeCapabilities({
        stats: {
          additions: 0,
          commitsAhead: 0,
          deletions: 0,
          formattedSummary: 'There are no changes to publish',
          hasChanges: false,
          stagedFiles: 0,
          unstagedFiles: 0,
        },
        suggestedBody: '',
        suggestedTitle: '',
      }),
    )

    renderModal()

    await waitFor(() => {
      expect(
        screen.getAllByText(/there are no changes to publish/i).length,
      ).toBeGreaterThanOrEqual(1)
    })

    expect(screen.getByRole('button', { name: /create pull request/i })).toBeDisabled()
  })

  it('creates a pull request with squash flag and renders success card with link', async () => {
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
          body: 'Summary of changes.',
          branchName: 'kratis/feature-9b3c2a3f',
          draft: false,
          squash: true,
          title: 'feat: new feature',
        }),
      )
    })

    expect(publishSpy.mock.calls[0][2]).not.toHaveProperty('baseBranch')

    await waitFor(() => {
      expect(screen.getByText(/pull request created successfully/i)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: /open pr #42/i })).toHaveAttribute(
        'href',
        'https://github.com/org/repo/pull/42',
      )
    })
  })

  it('requires a non-empty commit message before publishing', async () => {
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

    fireEvent.change(screen.getByLabelText(/commit message/i), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: /create pull request/i }))

    expect(publishSpy).not.toHaveBeenCalled()
  })

  it('handles generic repository where PRs are unsupported and pushes branch', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(
      makeCapabilities({
        repositoryType: 'GENERIC',
        stats: {
          additions: 10,
          commitsAhead: 1,
          deletions: 0,
          formattedSummary: '1 commit (+10, -0 lines)',
          hasChanges: true,
          stagedFiles: 0,
          unstagedFiles: 0,
        },
        suggestedBody: '',
        suggestedTitle: 'feat: commit updates',
        supportsPullRequests: false,
      }),
    )

    const pushSpy = vi.spyOn(publishApi, 'pushBranch').mockResolvedValue({
      branchName: 'kratis/feature-9b3c2a3f',
      commitSha: 'abcdef123456789',
      remoteRef: 'refs/heads/kratis/feature-9b3c2a3f',
      status: 'success',
    })

    renderModal()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /push branch/i })).toBeInTheDocument()
    })

    expect(screen.getByTestId('publish-plan')).toHaveTextContent(
      /automated pull requests are not supported for GENERIC repositories/i,
    )
    expect(screen.queryByRole('button', { name: /create pull request/i })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/description \/ summary/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/draft/i)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /push branch/i }))

    await waitFor(() => {
      expect(pushSpy).toHaveBeenCalledWith(
        'chat-123',
        '9b3c2a3f-55b7-47a7-83ac-6b9c592f2a07',
        expect.objectContaining({
          branchName: 'kratis/feature-9b3c2a3f',
          commitMessage: 'feat: commit updates',
        }),
      )
    })

    await waitFor(() => {
      expect(screen.getByText(/branch pushed successfully/i)).toBeInTheDocument()
    })
  })

  it('triggers download patch file on button click', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(
      makeCapabilities({ supportsPullRequests: false }),
    )

    const downloadSpy = vi.spyOn(publishApi, 'downloadPatchFile').mockResolvedValue()

    renderModal({ chatId: 'chat-dl', executionId: 'exec-dl-12345678' })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /download patch/i })).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /download patch/i }))

    await waitFor(() => {
      expect(downloadSpy).toHaveBeenCalledWith('chat-dl', 'exec-dl-12345678')
    })
  })

  it('locks the branch and pushes changes when a pull request already exists', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(
      makeCapabilities({
        publishedBranch: 'kratis/existing-branch',
        publishedPrNumber: 42,
        publishedPrUrl: 'https://github.com/org/repo/pull/42',
        suggestedTitle: 'feat: refine feature',
      }),
    )

    const publishSpy = vi.spyOn(publishApi, 'publishPullRequest').mockResolvedValue({
      baseBranch: 'main',
      headBranch: 'kratis/existing-branch',
      prNumber: 42,
      prUrl: 'https://github.com/org/repo/pull/42',
    })

    renderModal()

    await waitFor(() => {
      const plan = screen.getByTestId('publish-plan')
      expect(plan).toHaveTextContent('Pushes new commits to kratis/existing-branch')
      expect(plan).toHaveTextContent('updates pull request')
      expect(screen.getByRole('link', { name: '#42' })).toHaveAttribute(
        'href',
        'https://github.com/org/repo/pull/42',
      )
    })

    const branchInput = screen.getByLabelText(/feature branch/i)
    expect(branchInput).toHaveValue('kratis/existing-branch')
    expect(branchInput).toBeDisabled()

    expect(screen.getByLabelText(/commit message/i)).toHaveValue('feat: refine feature')
    expect(screen.queryByLabelText(/description \/ summary/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/draft/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /push to pr #42/i })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /push to pr #42/i }))

    await waitFor(() => {
      expect(publishSpy).toHaveBeenCalledWith(
        'chat-123',
        '9b3c2a3f-55b7-47a7-83ac-6b9c592f2a07',
        expect.objectContaining({
          branchName: 'kratis/existing-branch',
          title: 'feat: refine feature',
        }),
      )
    })

    expect(publishSpy.mock.calls[0][2]).not.toHaveProperty('draft')
    expect(publishSpy.mock.calls[0][2]).not.toHaveProperty('body')

    await waitFor(() => {
      expect(screen.getByText(/changes pushed/i)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: /open pr #42/i })).toHaveAttribute(
        'href',
        'https://github.com/org/repo/pull/42',
      )
    })
  })

  it('locks the branch when repushing to a previously pushed generic branch', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(
      makeCapabilities({
        publishedBranch: 'kratis/existing-branch',
        repositoryType: 'GENERIC',
        supportsPullRequests: false,
      }),
    )

    const pushSpy = vi.spyOn(publishApi, 'pushBranch').mockResolvedValue({
      branchName: 'kratis/existing-branch',
      commitSha: 'abcdef123456789',
      remoteRef: 'refs/heads/kratis/existing-branch',
      status: 'success',
    })

    renderModal()

    await waitFor(() => {
      expect(screen.getByTestId('publish-plan')).toHaveTextContent(
        'Pushes new commits to kratis/existing-branch',
      )
    })

    const branchInput = screen.getByLabelText(/feature branch/i)
    expect(branchInput).toHaveValue('kratis/existing-branch')
    expect(branchInput).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: /push branch/i }))

    await waitFor(() => {
      expect(pushSpy).toHaveBeenCalledWith(
        'chat-123',
        '9b3c2a3f-55b7-47a7-83ac-6b9c592f2a07',
        expect.objectContaining({
          branchName: 'kratis/existing-branch',
        }),
      )
    })
  })

  it('offers a rebase & retest escalation when publishing fails with a conflict', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(makeCapabilities())

    vi.spyOn(publishApi, 'publishPullRequest').mockRejectedValue(
      new ApiError('Your changes conflict with the latest commits on the target branch.', 409),
    )
    const steerSpy = vi.spyOn(diffApi, 'steerExecution').mockResolvedValue()

    renderModal({ executionStatus: 'RUNNING' })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /create pull request/i })).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /create pull request/i }))

    const escalateButton = await screen.findByRole('button', { name: /ask agent to rebase/i })
    expect(escalateButton).toBeInTheDocument()

    fireEvent.click(escalateButton)

    await waitFor(() => {
      expect(steerSpy).toHaveBeenCalledWith(
        'chat-123',
        '9b3c2a3f-55b7-47a7-83ac-6b9c592f2a07',
        expect.objectContaining({
          prompt: expect.stringContaining('Rebase your branch onto origin/main'),
        }),
      )
    })
  })

  it('does not offer escalation when the execution is not steerable', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(makeCapabilities())

    vi.spyOn(publishApi, 'publishPullRequest').mockRejectedValue(
      new ApiError('Your changes conflict with the latest commits on the target branch.', 409),
    )

    renderModal({ executionStatus: 'COMPLETED' })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /create pull request/i })).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /create pull request/i }))

    await screen.findByText(/Your changes conflict/i)
    expect(screen.queryByRole('button', { name: /ask agent to rebase/i })).not.toBeInTheDocument()
  })

  it('repopulates the correct commit data after closing and reopening the dialog', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(
      makeCapabilities({
        suggestedBody: 'Correct summary body.',
        suggestedTitle: 'feat: correct title',
      }),
    )

    const view = renderModal()

    await waitFor(() => {
      expect(screen.getByLabelText(/commit message/i)).toHaveValue('feat: correct title')
    })
    expect(screen.getByLabelText(/description \/ summary/i)).toHaveValue('Correct summary body.')

    const commonProps = {
      chatId: 'chat-123',
      executionId: '9b3c2a3f-55b7-47a7-83ac-6b9c592f2a07',
      onOpenChange: vi.fn(),
    }

    view.rerender(
      <QueryClientProvider client={queryClient}>
        <PublishModal {...commonProps} open={false} />
      </QueryClientProvider>,
    )
    view.rerender(
      <QueryClientProvider client={queryClient}>
        <PublishModal {...commonProps} open={true} />
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(screen.getByLabelText(/commit message/i)).toHaveValue('feat: correct title')
    })
    expect(screen.getByLabelText(/description \/ summary/i)).toHaveValue('Correct summary body.')
  })

  it('shows an error message instead of placeholders when capabilities fail to load', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockRejectedValue(
      new ApiError('Failed to generate PR publish summary via LLM', 500),
    )

    renderModal()

    await screen.findByText(/unable to load publish details/i)
    expect(
      screen.getByText('Failed to generate PR publish summary via LLM'),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText(/commit message/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/feature branch/i)).not.toBeInTheDocument()
  })

  it('renders full-screen on mobile with pinned header, scrollable body, and sticky footer', async () => {
    vi.spyOn(publishApi, 'fetchPublishCapabilities').mockResolvedValue(makeCapabilities())

    renderModal()

    await waitFor(() => {
      expect(screen.getByLabelText(/feature branch/i)).toBeInTheDocument()
    })

    const content = document.querySelector('[data-slot="dialog-content"]')
    expect(content).not.toBeNull()

    // Full-screen on mobile: dvh height tracks the visual viewport (shrinks with
    // the keyboard), edge-to-edge with no rounding
    expect(content).toHaveClass(
      'fixed',
      'inset-0',
      'flex',
      'flex-col',
      'h-dvh',
      'w-screen',
      'max-w-none',
      'translate-x-0',
      'translate-y-0',
      'overflow-hidden',
      'rounded-none',
      'p-4',
    )

    // Restored to a centered floating dialog on sm+ (widths/height/position overrides)
    expect(content).toHaveClass(
      'sm:h-auto',
      'sm:max-h-[90vh]',
      'sm:max-w-[720px]',
      'sm:translate-x-[-50%]',
      'sm:translate-y-[-50%]',
      'sm:rounded-lg',
      'md:max-w-[800px]',
    )

    // Header pinned at top, body scrolls internally, footer sticks above safe area
    expect(document.querySelector('[data-slot="dialog-header"]')).toHaveClass('shrink-0')
    const footer = document.querySelector('[data-slot="dialog-footer"]')
    expect(footer).toHaveClass('sticky', 'bottom-0', 'bg-card')
    expect(footer?.className).toContain('pb-[max(0.5rem,env(safe-area-inset-bottom))]')
  })
})

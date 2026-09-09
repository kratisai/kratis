import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SelectNewRepoList } from '@/components/repos/select-new-repo-list'

import {
  createMockRemoteRepository,
} from '../../support/test-factories'
import { renderWithProviders, screen, waitFor } from '../../support/test-render'

describe('SelectNewRepoList', () => {
  const noop = () => {}
  const defaultProps = {
    ingestImmediately: true,
    onIngestImmediatelyChange: noop,
  }

  beforeEach(() => {
    vi.restoreAllMocks()
    if (typeof globalThis.ResizeObserver === 'undefined') {
      globalThis.ResizeObserver = class ResizeObserver {
        disconnect() {}
        observe() {}
        unobserve() {}
      }
    }
  })

  it('shows loading state', () => {
    renderWithProviders(
      <SelectNewRepoList
        error={null}
        isLoading
        onBack={noop}
        onBatchOnboard={noop}
        onboardedRepoNames={new Set()}
        onEnterManually={noop}
        repos={[]}
        selectedProvider="github"
        totalRepoCount={0}
        {...defaultProps}
      />,
    )

    expect(screen.getByText(/Contacting .* host securely/i)).toBeInTheDocument()
  })

  it('shows error state and retries successfully', async () => {
    const onRefetchRepos = vi.fn(() => {})

    const { rerender, user } = renderWithProviders(
      <SelectNewRepoList
        error={new Error('Connection refused')}
        isLoading={false}
        onBack={noop}
        onBatchOnboard={noop}
        onboardedRepoNames={new Set()}
        onEnterManually={noop}
        onRefetchRepos={onRefetchRepos}
        repos={[]}
        selectedProvider="github"
        totalRepoCount={0}
        {...defaultProps}
      />,
    )

    expect(screen.getByText('Integration Sync Failed')).toBeInTheDocument()
    expect(screen.getByText('Connection refused')).toBeInTheDocument()

    const retryButton = screen.getByRole('button', { name: /retry connection/i })
    await user.click(retryButton)

    expect(onRefetchRepos).toHaveBeenCalled()

    rerender(
      <SelectNewRepoList
        error={null}
        isLoading={false}
        onBack={noop}
        onBatchOnboard={noop}
        onboardedRepoNames={new Set()}
        onEnterManually={noop}
        onRefetchRepos={onRefetchRepos}
        repos={[createMockRemoteRepository({ name: 'repo-retry' })]}
        selectedProvider="github"
        totalRepoCount={1}
        {...defaultProps}
      />,
    )

    await waitFor(() => {
      expect(screen.getByText('repo-retry')).toBeInTheDocument()
    })
  })

  it('shows empty state when no repositories are available', () => {
    renderWithProviders(
      <SelectNewRepoList
        error={null}
        isLoading={false}
        onBack={noop}
        onBatchOnboard={noop}
        onboardedRepoNames={new Set()}
        onEnterManually={noop}
        repos={[]}
        selectedProvider="github"
        totalRepoCount={0}
        {...defaultProps}
      />,
    )

    expect(screen.getByText('No repositories available for this credential')).toBeInTheDocument()
  })

  it('shows empty state when all repositories are already onboarded', () => {
    renderWithProviders(
      <SelectNewRepoList
        error={null}
        isLoading={false}
        onBack={noop}
        onBatchOnboard={noop}
        onboardedRepoNames={new Set(['repo-a'])}
        onEnterManually={noop}
        repos={[createMockRemoteRepository({ name: 'repo-a' })]}
        selectedProvider="github"
        totalRepoCount={1}
        {...defaultProps}
      />,
    )

    expect(screen.getByText('No new repositories found')).toBeInTheDocument()
  })

  it('shows the immediate ingestion toggle next to the search bar, defaulting to on', async () => {
    const onIngestImmediatelyChange = vi.fn()
    const { user } = renderWithProviders(
      <SelectNewRepoList
        error={null}
        ingestImmediately
        isLoading={false}
        onBack={noop}
        onBatchOnboard={noop}
        onboardedRepoNames={new Set()}
        onEnterManually={noop}
        onIngestImmediatelyChange={onIngestImmediatelyChange}
        repos={[
          createMockRemoteRepository({ name: 'repo-a' }),
          createMockRemoteRepository({ name: 'repo-b' }),
        ]}
        selectedProvider="github"
        totalRepoCount={2}
      />,
    )

    const checkbox = screen.getByRole('checkbox', {
      name: /start ingesting immediately/i,
    })
    expect(checkbox).toBeChecked()

    await user.click(checkbox)
    expect(onIngestImmediatelyChange).toHaveBeenCalledWith(false)
  })

  it('forwards the selected repositories to the batch onboard handler', async () => {
    const onBatchOnboard = vi.fn()
    const { user } = renderWithProviders(
      <SelectNewRepoList
        error={null}
        ingestImmediately={false}
        isLoading={false}
        onBack={noop}
        onBatchOnboard={onBatchOnboard}
        onboardedRepoNames={new Set()}
        onEnterManually={noop}
        onIngestImmediatelyChange={noop}
        repos={[createMockRemoteRepository({ name: 'repo-a' })]}
        selectedProvider="github"
        totalRepoCount={1}
      />,
    )

    await user.click(screen.getByText('repo-a'))
    await user.click(screen.getByRole('button', { name: /onboard 1 repo/i }))

    expect(onBatchOnboard).toHaveBeenCalledWith(new Set(['repo-a']))
  })
})

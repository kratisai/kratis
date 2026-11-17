import { within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/store/auth-store'

import { createMockRepository, createMockTeam } from '../support/test-factories'
import {
  addFetchHandler,
  jsonResponse,
  mockListTeams,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderIntegration,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'

describe('Team Wiki Flow and Navigation', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    vi.clearAllMocks()
  })

  afterEach(() => {
    useAuthStore.getState().logout()
  })

  function setupTwoRepoTeamMocks(options?: { rebuildSpy?: (body: unknown) => void }) {
    mockListTeams([createMockTeam()])

    const repo1 = createMockRepository({
      id: 'repo-1',
      ingestionStatus: 'SUCCESS',
      name: 'orders-api',
    })
    const repo2 = createMockRepository({
      id: 'repo-2',
      ingestionStatus: 'SUCCESS',
      name: 'billing-api',
    })

    const teamRootPage = {
      content: 'Team overview linking to [Orders Arch](/wiki/repo/orders-api/arch).',
      hasChildren: true,
      id: 'tw-page-1',
      orderIndex: 0,
      pageSlug: 'team-overview',
      parentPageId: null,
      repoName: null,
      title: 'Team Landscape',
    }

    const teamSubPage = {
      content: 'Core cluster cross-repo details.',
      hasChildren: false,
      id: 'tw-page-2',
      orderIndex: 0,
      pageSlug: 'core-cluster',
      parentPageId: 'tw-page-1',
      repoName: null,
      title: 'Core Cluster',
    }

    const ordersRootPage = {
      content: 'Orders root content.',
      hasChildren: true,
      id: 'repo-page-1',
      orderIndex: 0,
      pageSlug: 'overview',
      parentPageId: null,
      repoName: 'orders-api',
      title: 'Orders Overview',
    }

    const ordersArchPage = {
      content: 'Orders architecture details.',
      hasChildren: false,
      id: 'repo-page-2',
      orderIndex: 0,
      pageSlug: 'arch',
      parentPageId: 'repo-page-1',
      repoName: 'orders-api',
      title: 'Orders Architecture',
    }

    addFetchHandler((url, init) => {
      if (url.endsWith('/repositories')) {
        return jsonResponse([repo1, repo2])
      }
      if (url.endsWith('/wiki/status')) {
        return jsonResponse({
          activeBatch: {
            buildMode: 'FULL',
            completedAt: '2026-11-16T10:05:00Z',
            errorMessage: null,
            id: 'tw-batch-1',
            isActive: true,
            repoCount: 2,
            startedAt: '2026-11-16T10:00:00Z',
            status: 'SUCCESS',
            totalToolCalls: 4,
            triggerKind: 'AUTOMATIC',
          },
          deferralReason: 'NONE',
          dirty: false,
          ingestedRepoCount: 2,
          latestBatch: {
            buildMode: 'FULL',
            completedAt: '2026-11-16T10:05:00Z',
            errorMessage: null,
            id: 'tw-batch-1',
            isActive: true,
            repoCount: 2,
            startedAt: '2026-11-16T10:00:00Z',
            status: 'SUCCESS',
            totalToolCalls: 4,
            triggerKind: 'AUTOMATIC',
          },
          nextEligibleAt: null,
          nonStructuralChangedRepoCount: 0,
          recentBatches: [
            {
              buildMode: 'FULL',
              completedAt: '2026-11-16T10:05:00Z',
              errorMessage: null,
              id: 'tw-batch-1',
              isActive: true,
              repoCount: 2,
              startedAt: '2026-11-16T10:00:00Z',
              status: 'SUCCESS',
              totalToolCalls: 4,
              triggerKind: 'AUTOMATIC',
            },
          ],
        })
      }
      if (url.endsWith('/wiki/pages') && !url.includes('/repositories/')) {
        return jsonResponse([teamRootPage])
      }
      if (url.endsWith('/wiki/pages/tw-page-1/children')) {
        return jsonResponse([teamSubPage])
      }
      if (url.endsWith('/wiki/pages/tw-page-1')) {
        return jsonResponse(teamRootPage)
      }
      if (url.endsWith('/wiki/pages/tw-page-2')) {
        return jsonResponse(teamSubPage)
      }
      if (url.endsWith('/wiki/batches/tw-batch-1/logs')) {
        return jsonResponse([
          {
            createdAt: '2026-11-16T10:00:05Z',
            id: 'log-1',
            level: 'INFO',
            message: 'Starting FULL team wiki build',
            step: 'START',
          },
        ])
      }
      if (url.endsWith('/wiki/rebuild') && init.method === 'POST') {
        options?.rebuildSpy?.(init.body ? JSON.parse(String(init.body)) : {})
        return jsonResponse(
          {
            buildMode: 'INCREMENTAL',
            completedAt: null,
            errorMessage: null,
            id: 'tw-batch-2',
            isActive: false,
            repoCount: 2,
            startedAt: '2026-11-16T11:00:00Z',
            status: 'QUEUED',
            totalToolCalls: null,
            triggerKind: 'MANUAL',
          },
          202,
        )
      }
      if (url.endsWith('/repositories/repo-1/wiki/pages')) {
        return jsonResponse([ordersRootPage])
      }
      if (url.endsWith('/repositories/repo-1/wiki/pages/repo-page-1/children')) {
        return jsonResponse([ordersArchPage])
      }
      if (url.endsWith('/repositories/repo-1/wiki/pages/repo-page-1')) {
        return jsonResponse(ordersRootPage)
      }
      if (url.endsWith('/repositories/repo-1/wiki/pages/repo-page-2')) {
        return jsonResponse(ordersArchPage)
      }
      return null
    })
  }

  it('renders team wiki, cross-links to repo wiki, swaps sidebar inline, and opens logs drawer', async () => {
    const rebuildSpy = vi.fn()
    setupTwoRepoTeamMocks({ rebuildSpy })
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    const { user } = renderIntegration(['/wiki'])

    await waitFor(() => {
      expect(screen.getByText('Team overview linking to', { exact: false })).toBeInTheDocument()
    })

    // Sidebar displays Team Wiki tree + flat repo roots, hiding Topics on /wiki
    expect(screen.getByText('orders-api')).toBeInTheDocument()
    expect(screen.getByText('billing-api')).toBeInTheDocument()
    expect(screen.getByText('Up to date')).toBeInTheDocument()
    expect(screen.queryByText('Topics')).not.toBeInTheDocument()

    // Clicking a Team Wiki sub-page highlights that sub-page instead of the top-level page
    await user.click(screen.getByText('Core Cluster'))
    await waitFor(() => {
      expect(screen.getByText('Core cluster cross-repo details.')).toBeInTheDocument()
    })
    const sidebarSection = screen.getByTestId('wiki-sidebar-section')
    const subNavRow = within(sidebarSection).getByText('Core Cluster').closest('.cursor-pointer')
    const rootNavRow = within(sidebarSection).getByText('Team Landscape').closest('.cursor-pointer')
    expect(subNavRow).toHaveClass('bg-accent')
    expect(rootNavRow).not.toHaveClass('bg-accent')

    // Return to Team Landscape and click markdown link to /wiki/repo/orders-api/arch
    await user.click(within(sidebarSection).getByText('Team Landscape'))
    await waitFor(() => {
      expect(screen.getByRole('link', { name: 'Orders Arch' })).toBeInTheDocument()
    })

    // Open settings & logs drawer via cog button
    await user.click(screen.getByRole('button', { name: /team wiki settings and logs/i }))
    await waitFor(() => {
      expect(screen.getByText('Team Wiki Build History & Logs')).toBeInTheDocument()
      expect(screen.getByText(/Starting FULL team wiki build/)).toBeInTheDocument()
    })

    // Trigger manual Update Wiki from inside the drawer
    await user.click(screen.getByRole('button', { name: /update wiki/i }))
    await waitFor(() => {
      expect(rebuildSpy).toHaveBeenCalledWith({ mode: 'INCREMENTAL' })
    })
    await user.keyboard('{Escape}')

    // Click markdown link to /wiki/repo/orders-api/arch
    await user.click(screen.getByRole('link', { name: 'Orders Arch' }))

    await waitFor(() => {
      expect(screen.getByText('Orders architecture details.')).toBeInTheDocument()
    })

    // Sidebar swapped to orders-api with Back to Team Wiki button
    expect(screen.getByRole('button', { name: /back to team wiki/i })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /back to team wiki/i }))

    await waitFor(() => {
      expect(screen.getByText('Team overview linking to', { exact: false })).toBeInTheDocument()
    })
  })

  it('redirects single-ingested-repo team from /wiki to that repo wiki', async () => {
    mockListTeams([createMockTeam()])
    const repo1 = createMockRepository({
      id: 'repo-1',
      ingestionStatus: 'SUCCESS',
      name: 'solo-repo',
    })
    const soloPage = {
      content: 'Solo repo docs.',
      hasChildren: false,
      id: 'solo-1',
      orderIndex: 0,
      pageSlug: 'overview',
      parentPageId: null,
      repoName: 'solo-repo',
      title: 'Solo Overview',
    }
    addFetchHandler((url) => {
      if (url.endsWith('/repositories')) return jsonResponse([repo1])
      if (url.endsWith('/wiki/status')) {
        return jsonResponse({
          activeBatch: null,
          deferralReason: 'NONE',
          dirty: false,
          ingestedRepoCount: 1,
          latestBatch: null,
          nextEligibleAt: null,
          nonStructuralChangedRepoCount: 0,
          recentBatches: [],
        })
      }
      if (url.endsWith('/repositories/repo-1/wiki/pages')) return jsonResponse([soloPage])
      if (url.endsWith('/repositories/repo-1/wiki/pages/solo-1')) return jsonResponse(soloPage)
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/wiki'])

    await waitFor(() => {
      expect(screen.getByText('Solo repo docs.')).toBeInTheDocument()
    })
  })

  it('shows empty state on /wiki when no repositories are ingested', async () => {
    mockListTeams([createMockTeam()])
    addFetchHandler((url) => {
      if (url.endsWith('/repositories')) return jsonResponse([])
      if (url.endsWith('/wiki/status')) {
        return jsonResponse({
          activeBatch: null,
          deferralReason: 'NONE',
          dirty: false,
          ingestedRepoCount: 0,
          latestBatch: null,
          nextEligibleAt: null,
          nonStructuralChangedRepoCount: 0,
          recentBatches: [],
        })
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/wiki'])

    await waitFor(() => {
      expect(screen.getByText('No ingested repositories yet')).toBeInTheDocument()
    })
  })

  it('resolves slash-containing repo names in /wiki/repo/<org>/<repo>/<slug> URLs', async () => {
    mockListTeams([createMockTeam()])
    const bookingRepo = createMockRepository({
      id: 'repo-booking',
      ingestionStatus: 'SUCCESS',
      name: 'glark-newco/BookingService',
    })
    const bookingPage = {
      content: 'Booking Service architecture.',
      hasChildren: false,
      id: 'booking-page-1',
      orderIndex: 0,
      pageSlug: 'booking-service',
      parentPageId: null,
      repoName: 'glark-newco/BookingService',
      title: 'Booking Service',
    }
    addFetchHandler((url) => {
      if (url.endsWith('/repositories')) return jsonResponse([bookingRepo])
      if (url.endsWith('/wiki/status')) {
        return jsonResponse({
          activeBatch: null,
          deferralReason: 'NONE',
          dirty: false,
          ingestedRepoCount: 1,
          latestBatch: null,
          nextEligibleAt: null,
          nonStructuralChangedRepoCount: 0,
          recentBatches: [],
        })
      }
      if (url.endsWith('/repositories/repo-booking/wiki/pages')) return jsonResponse([bookingPage])
      if (url.endsWith('/repositories/repo-booking/wiki/pages/booking-page-1')) {
        return jsonResponse(bookingPage)
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/wiki/repo/glark-newco/BookingService/booking-service'])

    await waitFor(() => {
      expect(screen.getByText('Booking Service architecture.')).toBeInTheDocument()
    })
  })

  it('shows Not built yet badge when team has 2+ repos and no active batch', async () => {
    mockListTeams([createMockTeam()])
    const repo1 = createMockRepository({ id: 'repo-1', ingestionStatus: 'SUCCESS', name: 'svc-a' })
    const repo2 = createMockRepository({ id: 'repo-2', ingestionStatus: 'SUCCESS', name: 'svc-b' })
    addFetchHandler((url) => {
      if (url.endsWith('/repositories')) return jsonResponse([repo1, repo2])
      if (url.endsWith('/wiki/status')) {
        return jsonResponse({
          activeBatch: null,
          deferralReason: 'NONE',
          dirty: true,
          ingestedRepoCount: 2,
          latestBatch: null,
          nextEligibleAt: null,
          nonStructuralChangedRepoCount: 0,
          recentBatches: [],
        })
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/wiki'])

    await waitFor(() => {
      expect(screen.getByText('Team Wiki Not Built Yet')).toBeInTheDocument()
      expect(screen.getByText('Not built yet')).toBeInTheDocument()
    })
  })
})

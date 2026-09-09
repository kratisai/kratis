import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/store/auth-store'

import { createMockRepository, createMockTeam } from '../support/test-factories'
import {
  addFetchHandler,
  jsonResponse,
  mockGetWikiPage,
  mockListTeams,
  mockListWikiPageChildren,
  mockListWikiPages,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import { renderIntegration, screen, setAuthenticated, setUnauthenticated, waitFor } from '../support/test-render'

describe('Wiki Flow and Navigation', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    vi.clearAllMocks()
  })

  afterEach(() => {
    useAuthStore.getState().logout()
  })

  function setupMocks() {
    mockListTeams([createMockTeam()])

    addFetchHandler((url) => {
      if (url.includes('/repositories') && !url.includes('/wiki')) {
        return jsonResponse([
          createMockRepository({
            commitHash: 'abc123def456',
            ingestionStatus: 'SUCCESS',
            lastIngestedAt: '2024-01-15T10:00:00Z',
          }),
        ])
      }
      return null
    })

    mockListWikiPages([
      {
        content: 'Welcome to the main page.',
        hasChildren: true,
        id: 'page-1',
        orderIndex: 0,
        pageSlug: 'main-page',
        parentPageId: null,
        repoName: 'frontend-app',
        title: 'Main Page',
      },
    ])

    mockListWikiPageChildren('page-1', [
      {
        content: 'This is the sub-section of main page.',
        hasChildren: false,
        id: 'page-2',
        orderIndex: 0,
        pageSlug: 'sub-page',
        parentPageId: 'page-1',
        repoName: 'frontend-app',
        title: 'Sub Page',
      },
    ])

    mockGetWikiPage('page-1', {
      content: 'Welcome to the main page.',
      hasChildren: true,
      id: 'page-1',
      orderIndex: 0,
      pageSlug: 'main-page',
      parentPageId: null,
      repoName: 'frontend-app',
      title: 'Main Page',
    })

    mockGetWikiPage('page-2', {
      content: 'This is the sub-section of main page.',
      hasChildren: false,
      id: 'page-2',
      orderIndex: 0,
      pageSlug: 'sub-page',
      parentPageId: 'page-1',
      repoName: 'frontend-app',
      title: 'Sub Page',
    })
  }

  it('renders the wiki sidebar section and selected page content', async () => {
    setupMocks()
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    const { user } = renderIntegration(['/wiki/repo-1/main-page'])

    await waitFor(() => {
      expect(screen.getByText('Welcome to the main page.')).toBeInTheDocument()
    })

    expect(screen.getByTestId('wiki-sidebar-section')).toBeInTheDocument()
    expect(screen.getByTestId('wiki-content')).toBeInTheDocument()

    await waitFor(() => {
      expect(screen.getByText('Sub Page')).toBeInTheDocument()
    })

    await user.click(screen.getByText('Sub Page'))

    await waitFor(() => {
      expect(screen.getByText('This is the sub-section of main page.')).toBeInTheDocument()
    })

    expect(screen.getAllByText('Wiki').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('Main Page').length).toBeGreaterThanOrEqual(2)
    expect(screen.getAllByText('Sub Page').length).toBeGreaterThanOrEqual(2)
  })

  it('does not render the wiki sidebar section outside the wiki route', async () => {
    mockListTeams([createMockTeam()])

    addFetchHandler((url) => {
      if (url.includes('/repositories')) {
        return jsonResponse([createMockRepository()])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    renderIntegration(['/ask'])

    await waitFor(() => {
      expect(screen.getByText('Ask Kratis')).toBeInTheDocument()
    })

    expect(screen.queryByTestId('wiki-sidebar-section')).not.toBeInTheDocument()
  })

  it('renders full-width content without a second sidebar', async () => {
    setupMocks()
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    renderIntegration(['/wiki/repo-1/main-page'])

    await waitFor(() => {
      expect(screen.getByText('Welcome to the main page.')).toBeInTheDocument()
    })

    expect(screen.queryByTestId('wiki-desktop-sidebar')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /open wiki navigation/i })).not.toBeInTheDocument()
    expect(screen.queryByTestId('wiki-nav-drawer')).not.toBeInTheDocument()
    expect(screen.queryByTestId('wiki-nav-backdrop')).not.toBeInTheDocument()
  })

  it('toggles the wiki sidebar section via the header affordance', async () => {
    setupMocks()
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    const { user } = renderIntegration(['/wiki/repo-1/main-page'])

    await waitFor(() => {
      expect(screen.getByText('Welcome to the main page.')).toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.getByText('Sub Page')).toBeInTheDocument()
    })

    expect(screen.getByTestId('wiki-sidebar-section-content')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /collapse wiki section/i })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /collapse wiki section/i }))
    expect(screen.queryByTestId('wiki-sidebar-section-content')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /expand wiki section/i })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /expand wiki section/i }))
    expect(screen.getByTestId('wiki-sidebar-section-content')).toBeInTheDocument()
  })

  it('navigates to a page from the sidebar section', async () => {
    setupMocks()
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    const { user } = renderIntegration(['/wiki/repo-1/main-page'])

    await waitFor(() => {
      expect(screen.getByText('Welcome to the main page.')).toBeInTheDocument()
    })
    await waitFor(() => {
      expect(screen.getByText('Sub Page')).toBeInTheDocument()
    })

    await user.click(screen.getByText('Sub Page'))

    await waitFor(() => {
      expect(screen.getByText('This is the sub-section of main page.')).toBeInTheDocument()
    })
  })

  it('automatically redirects to the first top-level page when slug is omitted', async () => {
    setupMocks()
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    renderIntegration(['/wiki/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('Welcome to the main page.')).toBeInTheDocument()
    })
  })

  it('shows not found error view when repository is not found', async () => {
    mockListTeams([createMockTeam()])

    addFetchHandler((url) => {
      if (url.includes('/repositories') && !url.includes('/wiki')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })

    renderIntegration(['/wiki/invalid-repo/main-page'])

    await waitFor(() => {
      expect(screen.getByText('Repository not found')).toBeInTheDocument()
    })
  })

  it('loads wiki tree with nested children (hasChildren pages)', async () => {
    mockListTeams([createMockTeam()])

    addFetchHandler((url) => {
      if (url.includes('/repositories') && !url.includes('/wiki')) {
        return jsonResponse([
          createMockRepository({
            commitHash: 'abc123def456',
            id: 'repo-tree',
            ingestionStatus: 'SUCCESS',
            lastIngestedAt: '2024-01-15T10:00:00Z',
            name: 'tree-repo',
          }),
        ])
      }
      return null
    })

    mockListWikiPages([
      {
        content: 'Top level page',
        hasChildren: true,
        id: 'page-1',
        parentPageId: null,
        repoId: 'repo-tree',
        slug: 'top-page',
        title: 'Top Page',
      },
    ])

    mockGetWikiPage('page-1', {
      content: 'Top level page',
      hasChildren: true,
      id: 'page-1',
      parentPageId: null,
      repoId: 'repo-tree',
      slug: 'top-page',
      title: 'Top Page',
    })

    mockListWikiPageChildren('page-1', [
      {
        content: 'Child page content',
        hasChildren: true,
        id: 'page-2',
        parentPageId: 'page-1',
        repoId: 'repo-tree',
        slug: 'child-page',
        title: 'Child Page',
      },
    ])

    mockListWikiPageChildren('page-2', [
      {
        content: 'Grandchild content',
        hasChildren: false,
        id: 'page-3',
        parentPageId: 'page-2',
        repoId: 'repo-tree',
        slug: 'grandchild-page',
        title: 'Grandchild Page',
      },
    ])

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    renderIntegration(['/wiki/repo-tree/top-page'])

    await waitFor(() => {
      expect(screen.getByText('Top Page')).toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.getByText('Child Page')).toBeInTheDocument()
    })
  })
})

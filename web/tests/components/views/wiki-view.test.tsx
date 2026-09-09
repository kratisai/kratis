import { beforeEach, describe, expect, it } from 'vitest'

import {
  addFetchHandler,
  setupFetchMock,
} from '../../support/test-fetch-mocks'
import {
  renderWithRouter,
  screen,
  setAuthenticated,
  waitFor,
} from '../../support/test-render'

// Mock data
const mockRepos = [
  {
    branch: 'main',
    createdAt: '2024-01-01T00:00:00Z',
    id: 'repo-1',
    name: 'my-repo',
    teamId: 'team-1',
    updatedAt: '2024-01-01T00:00:00Z',
    url: 'https://github.com/user/my-repo.git',
  },
]

const mockTopPages = [
  {
    content: '# Introduction\nWelcome to my repository!',
    hasChildren: true,
    id: 'page-1',
    orderIndex: 0,
    pageSlug: 'intro',
    parentPageId: null,
    repoName: 'my-repo',
    title: 'Introduction',
  },
  {
    content: '# Configuration\nHere is how you configure it.',
    hasChildren: false,
    id: 'page-2',
    orderIndex: 1,
    pageSlug: 'config',
    parentPageId: null,
    repoName: 'my-repo',
    title: 'Configuration',
  },
]

const mockChildPagesLevel2 = [
  {
    content: '# Setup Guide\nFollow these steps to setup.',
    hasChildren: true,
    id: 'page-3',
    orderIndex: 0,
    pageSlug: 'setup',
    parentPageId: 'page-1',
    repoName: 'my-repo',
    title: 'Setup Guide',
  },
]

const mockChildPagesLevel3 = [
  {
    content: '# Advanced Setup\nFor power users only.',
    hasChildren: false,
    id: 'page-4',
    orderIndex: 0,
    pageSlug: 'advanced',
    parentPageId: 'page-3',
    repoName: 'my-repo',
    title: 'Advanced Setup',
  },
]

function setupWikiApiMocks() {
  addFetchHandler((url) => {
    // List repos
    if (url.includes('/api/v1/teams/team-1/repositories') && !url.includes('/wiki/')) {
      return new Response(JSON.stringify(mockRepos), {
        headers: { 'Content-Type': 'application/json' },
      })
    }
    // Top level wiki pages
    if (url.includes('/wiki/pages') && !url.includes('/children') && url.endsWith('/pages')) {
      return new Response(JSON.stringify(mockTopPages), {
        headers: { 'Content-Type': 'application/json' },
      })
    }
    // Level 2 children of page-1
    if (url.includes('/wiki/pages/page-1/children')) {
      return new Response(JSON.stringify(mockChildPagesLevel2), {
        headers: { 'Content-Type': 'application/json' },
      })
    }
    // Level 3 children of page-3
    if (url.includes('/wiki/pages/page-3/children')) {
      return new Response(JSON.stringify(mockChildPagesLevel3), {
        headers: { 'Content-Type': 'application/json' },
      })
    }
    // Page details
    if (url.includes('/wiki/pages/page-1')) {
      return new Response(JSON.stringify(mockTopPages[0]), {
        headers: { 'Content-Type': 'application/json' },
      })
    }
    if (url.includes('/wiki/pages/page-2')) {
      return new Response(JSON.stringify(mockTopPages[1]), {
        headers: { 'Content-Type': 'application/json' },
      })
    }
    if (url.includes('/wiki/pages/page-3')) {
      return new Response(JSON.stringify(mockChildPagesLevel2[0]), {
        headers: { 'Content-Type': 'application/json' },
      })
    }
    if (url.includes('/wiki/pages/page-4')) {
      return new Response(JSON.stringify(mockChildPagesLevel3[0]), {
        headers: { 'Content-Type': 'application/json' },
      })
    }
    return null
  })
}

describe('WikiView Hierarchical Navigation', () => {
  setupFetchMock()

  beforeEach(() => {
    setAuthenticated({ teamId: 'team-1' })
    setupWikiApiMocks()
  })

  it('redirects to the first page when visiting /wiki/$repoId without a slug', async () => {
    renderWithRouter(['/wiki/repo-1'])

    // Should load the tree and select/navigate to 'intro'
    await waitFor(
      () => {
        expect(screen.getAllByText('Introduction').length).toBeGreaterThan(0)
        expect(screen.getByText('Welcome to my repository!')).toBeInTheDocument()
      },
      { timeout: 5000 },
    )
  })

  it('displays the full hierarchy up to 3 levels of nesting', async () => {
    renderWithRouter(['/wiki/repo-1/advanced'])

    // Since we are viewing 'advanced', parent pages should be auto-expanded.
    // Let's verify all 3 levels of nested pages are visible:
    // Level 1: Introduction, Configuration
    // Level 2: Setup Guide
    // Level 3: Advanced Setup
    await waitFor(() => {
      expect(screen.getAllByText('Introduction').length).toBeGreaterThan(0)
      expect(screen.getByText('Configuration')).toBeInTheDocument()
      expect(screen.getAllByText('Setup Guide').length).toBeGreaterThan(0)
      expect(screen.getByText('Advanced Setup')).toBeInTheDocument()
    })

    // Content should show Advanced Setup details
    await waitFor(() => {
      expect(screen.getByText('For power users only.')).toBeInTheDocument()
    })

    // Breadcrumbs should show Wiki > Introduction > Setup Guide > Advanced Setup
    expect(screen.getAllByText('Setup Guide').length).toBeGreaterThan(0)
    const wikiCrumb = screen.getAllByText('Wiki')
    expect(wikiCrumb.length).toBeGreaterThan(0)
  })

  it('allows clicking a link in the left-nav to navigate to another page', async () => {
    const { user } = renderWithRouter(['/wiki/repo-1/intro'])

    await waitFor(() => {
      expect(screen.getByText('Welcome to my repository!')).toBeInTheDocument()
    })

    // Click Configuration
    const configBtn = screen.getByText('Configuration')
    await user.click(configBtn)

    // Should load Configuration page details
    await waitFor(() => {
      expect(screen.getByText('Here is how you configure it.')).toBeInTheDocument()
    })
  })

  it('does not contain the old "Browse child pages" link at the bottom', async () => {
    renderWithRouter(['/wiki/repo-1/intro'])

    await waitFor(() => {
      expect(screen.getByText('Welcome to my repository!')).toBeInTheDocument()
    })

    // "Browse child pages" button/link should not be present anymore
    expect(screen.queryByRole('button', { name: /browse child pages/i })).toBeNull()
  })
})

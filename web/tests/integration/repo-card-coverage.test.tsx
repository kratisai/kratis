import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { RepositoryDto } from '@/types/auth-types'

import { useUIStore } from '@/store/ui-store'

import {
  addFetchHandler,
  jsonResponse,
  mockListTeams,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderWithRouter,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'

const team = {
  createdAt: '2024-01-01T00:00:00Z',
  id: 'team-1',
  isDefault: true,
  name: 'Test Team',
  role: 'owner',
  tavilyApiKeyConfigured: false,
}

function mockRepos(repos: RepositoryDto[]) {
  addFetchHandler((url, options) => {
    const match = url.match(/\/api\/v1\/teams\/[^/]+\/repositories$/)
    if (match && (!options.method || options.method === 'GET')) {
      return jsonResponse(repos)
    }
    return null
  })
}

describe('Repository Card Coverage', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    vi.stubGlobal('open', vi.fn())
    useUIStore.setState({
      selectedModelName: 'gpt-4',
      selectedProviderId: 'provider-1',
    })
  })

  it('renders provider logos and ingestion status badges for each host', async () => {
    mockListTeams([team])
    mockRepos([
      {
        branch: 'main',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-github',
        ingestionStatus: 'SUCCESS',
        name: 'github-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/org/repo.git',
      },
      {
        branch: 'main',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-gitlab',
        ingestionStatus: 'FAILED',
        name: 'gitlab-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://gitlab.com/org/repo.git',
      },
      {
        branch: 'main',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-bitbucket',
        name: 'bitbucket-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://bitbucket.org/org/repo.git',
      },
      {
        branch: 'main',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-azure',
        ingestionStatus: 'SUCCESS',
        name: 'azure-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://dev.azure.com/org/project/_git/repo',
      },
      {
        branch: 'main',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-custom',
        ingestionStatus: 'SUCCESS',
        name: 'custom-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://gitea.example.com/org/repo.git',
      },
    ])

    setAuthenticated({ teamId: 'team-1' })
    renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('github-repo')).toBeInTheDocument()
    })

    for (const name of ['github-repo', 'gitlab-repo', 'bitbucket-repo', 'azure-repo', 'custom-repo']) {
      const card = screen.getByText(name).closest('[data-slot="card"]') as HTMLElement
      expect(card.querySelector('.h-10.w-10 svg')).toBeInTheDocument()
    }

    expect(screen.getAllByText('Success').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText('Failed')).toBeInTheDocument()
    expect(screen.getByText('Not Ingested')).toBeInTheDocument()
  })

  it('does not render provider or credential badges on the card', async () => {
    mockListTeams([team])
    mockRepos([
      {
        branch: 'main',
        createdAt: '2024-01-01T00:00:00Z',
        credentialId: 'cred-1',
        id: 'repo-auth',
        name: 'auth-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/org/auth-repo.git',
      },
    ])

    setAuthenticated({ teamId: 'team-1' })
    renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('auth-repo')).toBeInTheDocument()
    })

    expect(screen.queryByText('Authenticated')).not.toBeInTheDocument()
    expect(screen.queryByText('GitHub')).not.toBeInTheDocument()
  })

  it('displays commit hash and last ingested time', async () => {
    mockListTeams([team])
    mockRepos([
      {
        branch: 'main',
        commitHash: 'abc1234567890def',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-meta',
        lastIngestedAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
        name: 'meta-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/org/meta-repo.git',
      },
    ])

    setAuthenticated({ teamId: 'team-1' })
    renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('meta-repo')).toBeInTheDocument()
    })

    expect(screen.getByText('abc1234')).toBeInTheDocument()
    expect(screen.getByText(/m ago/)).toBeInTheDocument()
  })

  it('navigates to wiki when clicking view wiki', async () => {
    mockListTeams([team])
    mockRepos([
      {
        branch: 'main',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-wiki',
        name: 'wiki-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/org/wiki-repo.git',
      },
    ])

    // Mock wiki top-level pages so the wiki view can render
    addFetchHandler((url) => {
      if (url.includes('/wiki/pages') && url.endsWith('/pages')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('wiki-repo')).toBeInTheDocument()
    })

    const wikiButton = screen.getByRole('button', { name: /view wiki/i })
    await user.click(wikiButton)

    await waitFor(() => {
      expect(screen.getByText('No pages found')).toBeInTheDocument()
    })
  })

  it('starts an Ask Kratis chat from the repository card', async () => {
    mockListTeams([team])
    mockRepos([
      {
        branch: 'main',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-ask',
        name: 'ask-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/org/ask-repo.git',
      },
    ])

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('ask-repo')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /ask kratis/i }))

    await waitFor(() => {
      expect(screen.getByText('Chat')).toBeInTheDocument()
    })
  })

  it('stacks the card header actions below the title on narrow screens', async () => {
    mockListTeams([team])
    mockRepos([
      {
        branch: 'main',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-stack',
        name: 'stack-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/org/stack-repo.git',
      },
    ])

    setAuthenticated({ teamId: 'team-1' })
    renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('stack-repo')).toBeInTheDocument()
    })

    const card = screen.getByText('stack-repo').closest('[data-slot="card"]') as HTMLElement
    expect(card).toHaveClass('min-w-0')
    const headerRow = card.querySelector('[data-slot="card-header"]')
      ?.firstElementChild as HTMLElement
    expect(headerRow).toHaveClass('flex-col')
    expect(headerRow).toHaveClass('md:flex-row')
    expect(headerRow).toHaveClass('md:justify-between')
    expect(headerRow).toHaveClass('min-w-0')

    const identity = headerRow.firstElementChild as HTMLElement
    expect(identity).toHaveClass('space-y-1')
    expect(identity).toHaveClass('min-w-0')
    expect(screen.getByText('stack-repo')).toHaveClass('truncate')
    expect(screen.getByText('https://github.com/org/stack-repo.git')).toHaveClass('truncate')

    const actions = headerRow.querySelector('.text-destructive')
    expect(actions?.parentElement).toHaveClass('justify-end')
  })

  it('opens external source link when clicking external link', async () => {
    const openSpy = vi.fn()
    vi.stubGlobal('open', openSpy)

    mockListTeams([team])
    mockRepos([
      {
        branch: 'main',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-ext',
        name: 'external-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/org/external-repo.git',
      },
    ])

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('external-repo')).toBeInTheDocument()
    })

    // External link button is the third icon button in the card row
    const card = screen.getByText('external-repo').closest('[data-slot="card"]') as HTMLElement
    const externalButton = card.querySelectorAll('button')[2]
    await user.click(externalButton)

    expect(openSpy).toHaveBeenCalledWith(
      'https://github.com/org/external-repo.git',
      '_blank',
    )
  })

  it('opens edit dialog when clicking settings', async () => {
    mockListTeams([team])
    mockRepos([
      {
        branch: 'main',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-edit',
        name: 'edit-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/org/edit-repo.git',
      },
    ])

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('edit-repo')).toBeInTheDocument()
    })

    const card = screen.getByText('edit-repo').closest('[data-slot="card"]') as HTMLElement
    const settingsButton = card.querySelectorAll('button')[3]
    await user.click(settingsButton)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Edit Repository Settings' })).toBeInTheDocument()
    })
  })

  it('opens delete confirmation when clicking trash', async () => {
    mockListTeams([team])
    mockRepos([
      {
        branch: 'main',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-delete',
        name: 'delete-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/org/delete-repo.git',
      },
    ])

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('delete-repo')).toBeInTheDocument()
    })

    const card = screen.getByText('delete-repo').closest('[data-slot="card"]') as HTMLElement
    const trashButton = card.querySelector('.text-destructive') as HTMLElement
    await user.click(trashButton)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Delete Repository' })).toBeInTheDocument()
    })
  })
})

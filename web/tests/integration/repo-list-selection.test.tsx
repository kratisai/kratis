import { beforeEach, describe, expect, it } from 'vitest'

import {
  createMockRemoteRepository,
  createMockTeam,
} from '../support/test-factories'
import {
  addFetchHandler,
  jsonResponse,
  mockListCredentials,
  mockListRepositories,
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

describe('Repository List Selection', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  async function openReposStep() {
    mockListTeams([createMockTeam()])
    mockListRepositories([])
    mockListCredentials([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'cred-1',
        name: 'Existing Credential',
        type: 'GITLAB',
      },
    ])

    setAuthenticated({ teamId: 'team-1' })
    const result = renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    await result.user.click(screen.getByRole('button', { name: /add repository/i }))

    await waitFor(() => {
      expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
    })

    await result.user.click(screen.getByRole('button', { name: /gitlab/i }))
    await result.user.click(screen.getByRole('button', { name: /next/i }))

    await waitFor(() => {
      expect(screen.getByText('Authenticate GITLAB')).toBeInTheDocument()
    })

    await result.user.click(screen.getByText('Existing Credential'))
    await waitFor(() => {
      expect(screen.getByText('Existing Credential')).toBeInTheDocument()
    })

    await result.user.click(screen.getByRole('button', { name: /next/i }))

    await waitFor(() => {
      expect(screen.getByText('Select Remote Repositories')).toBeInTheDocument()
    })

    return result
  }

  it('filters repositories by search term', async () => {
    addFetchHandler((url) => {
      if (url.includes('/available-repos')) {
        return jsonResponse([
          createMockRemoteRepository({ name: 'alpha-service' }),
          createMockRemoteRepository({ name: 'beta-web' }),
          createMockRemoteRepository({ name: 'gamma-lib' }),
        ])
      }
      return null
    })

    const { user } = await openReposStep()

    await waitFor(() => {
      expect(screen.getByText('alpha-service')).toBeInTheDocument()
    })
    expect(screen.getByText('beta-web')).toBeInTheDocument()
    expect(screen.getByText('gamma-lib')).toBeInTheDocument()

    const searchInput = screen.getByPlaceholderText('Search remote repositories...')
    await user.type(searchInput, 'alpha')

    await waitFor(() => {
      expect(screen.queryByText('beta-web')).not.toBeInTheDocument()
    })
    expect(screen.queryByText('gamma-lib')).not.toBeInTheDocument()
    expect(screen.getByText('alpha-service')).toBeInTheDocument()
  })

  it('selects all and deselects all repositories', async () => {
    addFetchHandler((url) => {
      if (url.includes('/available-repos')) {
        return jsonResponse([
          createMockRemoteRepository({ name: 'repo-a' }),
          createMockRemoteRepository({ name: 'repo-b' }),
        ])
      }
      return null
    })

    const { user } = await openReposStep()

    await waitFor(() => {
      expect(screen.getByText('repo-a')).toBeInTheDocument()
    })

    const selectAllButton = screen.getByRole('button', { name: /select all/i })
    await user.click(selectAllButton)

    await waitFor(() => {
      expect(screen.getByText('2 selected')).toBeInTheDocument()
    })

    await user.click(selectAllButton)

    await waitFor(() => {
      expect(screen.queryByText('2 selected')).not.toBeInTheDocument()
    })
  })

  it('shows empty state when no repositories are available', async () => {
    addFetchHandler((url) => {
      if (url.includes('/available-repos')) {
        return jsonResponse([])
      }
      return null
    })

    await openReposStep()

    await waitFor(() => {
      expect(screen.getByText('No repositories available for this credential')).toBeInTheDocument()
    })
    expect(
      screen.getByText(/Check credentials permissions or verify App installations./),
    ).toBeInTheDocument()
  })

  it('shows empty state when all repositories are already onboarded', async () => {
    addFetchHandler((url, options) => {
      const reposMatch = url.match(/\/api\/v1\/teams\/[^/]+\/repositories$/)
      if (reposMatch && (!options.method || options.method === 'GET')) {
        return jsonResponse([
          {
            branch: 'main',
            createdAt: '2024-01-01T00:00:00Z',
            id: 'repo-onboarded',
            name: 'repo-a',
            teamId: 'team-1',
            updatedAt: '2024-01-01T00:00:00Z',
            url: 'https://gitlab.com/user/repo-a.git',
          },
        ])
      }
      if (url.includes('/available-repos')) {
        return jsonResponse([createMockRemoteRepository({ name: 'repo-a' })])
      }
      return null
    })

    await openReposStep()

    await waitFor(() => {
      expect(screen.getByText('No new repositories found')).toBeInTheDocument()
    })
    expect(
      screen.getByText(/All available repos have been onboarded, or try a different credential./),
    ).toBeInTheDocument()
  })

  it('onboards multiple selected repositories in batch', async () => {
    const createdRepos: Array<{ name: string; url: string }> = []
    const ingestedRepoIds: string[] = []

    // Register the ingest handler first so the create handler (which matches any
    // POST to /repositories) does not swallow the /ingest requests.
    addFetchHandler((url, options) => {
      const match = url.match(/\/repositories\/([^/]+)\/ingest$/)
      if (match && options.method === 'POST') {
        ingestedRepoIds.push(match[1])
        return jsonResponse({ batchId: `batch-${match[1]}`, status: 'QUEUED' }, 202)
      }
      return null
    })

    addFetchHandler((url, options) => {
      if (url.includes('/available-repos')) {
        return jsonResponse([
          createMockRemoteRepository({ name: 'batch-a' }),
          createMockRemoteRepository({ name: 'batch-b' }),
        ])
      }
      if (url.includes('/repositories') && options.method === 'POST') {
        const body = JSON.parse(options.body as string)
        createdRepos.push({ name: body.name, url: body.url })
        return jsonResponse(
          {
            branch: 'main',
            createdAt: '2024-01-01T00:00:00Z',
            id: `repo-${body.name}`,
            name: body.name,
            teamId: 'team-1',
            updatedAt: '2024-01-01T00:00:00Z',
            url: body.url,
          },
          201,
        )
      }
      return null
    })

    const { user } = await openReposStep()

    await waitFor(() => {
      expect(screen.getByText('batch-a')).toBeInTheDocument()
    })

    await user.click(screen.getByText('batch-a'))
    await user.click(screen.getByText('batch-b'))

    await waitFor(() => {
      expect(screen.getByText('2 selected')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /onboard 2 repos/i }))

    await waitFor(() => {
      expect(screen.getAllByText('Repository added successfully').length).toBeGreaterThanOrEqual(1)
    })

    expect(createdRepos).toHaveLength(2)
    expect(createdRepos.map((r) => r.name).sort()).toEqual(['batch-a', 'batch-b'])

    // Both created repositories must request ingestion, not just the last one.
    await waitFor(() => {
      expect(ingestedRepoIds.sort()).toEqual(['repo-batch-a', 'repo-batch-b'])
    })
  })

  it('navigates to manual entry when clicking enter settings manually', async () => {
    addFetchHandler((url) => {
      if (url.includes('/available-repos')) {
        return jsonResponse([createMockRemoteRepository()])
      }
      return null
    })

    const { user } = await openReposStep()

    await waitFor(() => {
      expect(screen.getByText('repo')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /enter settings manually/i }))

    await waitFor(() => {
      expect(screen.getByText('Onboard Settings')).toBeInTheDocument()
      expect(screen.getByLabelText('Display Name')).toBeInTheDocument()
    })
  })
})

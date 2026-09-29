import { beforeEach, describe, expect, it } from 'vitest'

import { useAuthStore } from '@/store/auth-store'

import {
  mockListRepositories,
  mockListTeams,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderWithRouter,
  screen,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'

describe('Ingestion Metadata Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  it('displays commit hash and relative timestamp for successfully ingested repository', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    // Mock repository with ingestion metadata
    const fiveMinutesAgo = new Date(Date.now() - 5 * 60 * 1000).toISOString()
    mockListRepositories([
      {
        branch: 'main',
        commitHash: 'abc123def456',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-1',
        ingestionStatus: 'SUCCESS',
        lastIngestedAt: fiveMinutesAgo,
        name: 'my-project',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/user/my-project.git',
      },
    ])

    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600
    )
    useAuthStore.getState().setCurrentTeamId('team-1')

    renderWithRouter(["/repos"])

    // Wait for repo to load
    await waitFor(() => {
      expect(screen.getByText('my-project')).toBeInTheDocument()
    })

    // Verify commit hash is displayed (truncated to 7 chars)
    expect(screen.getByText('abc123d')).toBeInTheDocument()

    // Verify relative timestamp is displayed
    expect(screen.getByText('5m ago')).toBeInTheDocument()
  })

  it('does not display commit hash when repository has not been ingested', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    // Mock repository without ingestion metadata
    mockListRepositories([
      {
        branch: 'main',
        commitHash: null,
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-1',
        ingestionStatus: null,
        lastIngestedAt: null,
        name: 'fresh-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/user/fresh-repo.git',
      },
    ])

    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600
    )
    useAuthStore.getState().setCurrentTeamId('team-1')

    renderWithRouter(["/repos"])

    // Wait for repo to load
    await waitFor(() => {
      expect(screen.getByText('fresh-repo')).toBeInTheDocument()
    })

    // Verify commit hash is NOT displayed
    expect(screen.queryByText(/^[a-f0-9]{7}$/)).not.toBeInTheDocument()

    // Verify relative timestamp is NOT displayed
    expect(screen.queryByText(/ago$/)).not.toBeInTheDocument()
  })

  it('displays correct relative time for different ingestion ages', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    // Create timestamps for different ages
    const twoHoursAgo = new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString()
    const threeDaysAgo = new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString()

    mockListRepositories([
      {
        branch: 'main',
        commitHash: 'aaa111bbb222',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-1',
        ingestionStatus: 'SUCCESS',
        lastIngestedAt: twoHoursAgo,
        name: 'recent-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/user/recent-repo.git',
      },
      {
        branch: 'develop',
        commitHash: 'ccc333ddd444',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-2',
        ingestionStatus: 'SUCCESS',
        lastIngestedAt: threeDaysAgo,
        name: 'old-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/user/old-repo.git',
      },
    ])

    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600
    )
    useAuthStore.getState().setCurrentTeamId('team-1')

    renderWithRouter(["/repos"])

    // Wait for repos to load
    await waitFor(() => {
      expect(screen.getByText('recent-repo')).toBeInTheDocument()
      expect(screen.getByText('old-repo')).toBeInTheDocument()
    })

    // Verify relative timestamps
    expect(screen.getByText('2h ago')).toBeInTheDocument()
    expect(screen.getByText('3d ago')).toBeInTheDocument()

    // Verify commit hashes (truncated)
    expect(screen.getByText('aaa111b')).toBeInTheDocument()
    expect(screen.getByText('ccc333d')).toBeInTheDocument()
  })

  it('shows processing status without commit hash or timestamp', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    // Mock repository with PROCESSING status
    mockListRepositories([
      {
        branch: 'main',
        commitHash: null,
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-1',
        ingestionStatus: 'PROCESSING',
        lastIngestedAt: null,
        name: 'processing-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/user/processing-repo.git',
      },
    ])

    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600
    )
    useAuthStore.getState().setCurrentTeamId('team-1')

    renderWithRouter(["/repos"])

    // Wait for repo to load
    await waitFor(() => {
      expect(screen.getByText('processing-repo')).toBeInTheDocument()
    })

    // Verify commit hash is NOT displayed for processing repos
    expect(screen.queryByText(/^[a-f0-9]{7}$/)).not.toBeInTheDocument()

    // Verify relative timestamp is NOT displayed
    expect(screen.queryByText(/ago$/)).not.toBeInTheDocument()
  })

  it('displays commit hash for repository with failed ingestion', async () => {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])

    const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000).toISOString()

    // Mock repository with FAILED status but with commit hash from previous attempt
    mockListRepositories([
      {
        branch: 'main',
        commitHash: 'fail123hash',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-1',
        ingestionStatus: 'FAILED',
        lastIngestedAt: oneHourAgo,
        name: 'failed-repo',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/user/failed-repo.git',
      },
    ])

    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600
    )
    useAuthStore.getState().setCurrentTeamId('team-1')

    renderWithRouter(["/repos"])

    // Wait for repo to load
    await waitFor(() => {
      expect(screen.getByText('failed-repo')).toBeInTheDocument()
    })

    // Verify commit hash is displayed even for failed repos
    expect(screen.getByText('fail123')).toBeInTheDocument()

    // Verify relative timestamp is displayed
    expect(screen.getByText('1h ago')).toBeInTheDocument()
  })
})

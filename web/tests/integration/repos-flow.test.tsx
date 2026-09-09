import { fireEvent, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { UpdateRepositoryRequest } from '@/lib/repo-api'

import { useAuthStore } from '@/store/auth-store'

import {
  addFetchHandler,
  jsonResponse,
  mockCreateRepository,
  mockListRepositories,
  mockListTeams,
  mockTriggerIngestion,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderWithRouter,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'
import { setupConnected } from '../support/test-websocket'

describe('Repositories Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  it('shows empty state when no team is selected', async () => {
    setAuthenticated({ userId: 'user-1' }) // Authenticated but no team selected
    renderWithRouter(['/repos'])

    // Wait for router to render the initial route
    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })
    expect(screen.getByText('Connect and manage your code repositories')).toBeInTheDocument()
  })

  it('loads repositories when team is selected', async () => {
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
    mockListRepositories([
      {
        branch: 'main',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-1',
        name: 'frontend-app',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/user/frontend-app.git',
      },
    ])

    setAuthenticated({ teamId: 'team-1' })
    renderWithRouter(['/repos'])

    // Wait for repos to load
    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })
  })

  it('opens add repository dialog when clicking Add Repository button', async () => {
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
    mockListRepositories([])

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    // Wait for view to render
    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    // Click "Add Repository"
    const addButton = screen.getByRole('button', { name: /add repository/i })
    await user.click(addButton)

    // Dialog title should show provider selection step
    await waitFor(() => {
      expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
    })
  })

  it('creates a repository successfully', async () => {
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
    mockListRepositories([])
    mockTriggerIngestion('repo-1')
    mockCreateRepository()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    // Wait for view to render
    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    // Open add dialog
    const addButton = screen.getByRole('button', { name: /add repository/i })
    await user.click(addButton)

    // Step 1: Provider selection
    await waitFor(() => {
      expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
    })

    // Click Public Repository provider
    const publicRepoButton = screen.getByText(/public repository/i).closest('button')
    await user.click(publicRepoButton!)

    // Click NEXT to go to details step
    await user.click(screen.getByRole('button', { name: /next/i }))

    // Should go directly to manual details form
    await waitFor(() => {
      expect(screen.getByLabelText('Display Name')).toBeInTheDocument()
    })

    // Fill form - use getByLabelText since Label uses htmlFor
    await user.type(screen.getByLabelText('Display Name'), 'my-new-repo')
    await user.type(screen.getByLabelText('Repository Git URL'), 'https://github.com/user/repo.git')

    // Submit - find the submit button in the dialog (the one with "Onboard Repository" text)
    const submitButton = screen.getByRole('button', { name: /onboard repository/i })
    await user.click(submitButton)

    // Success toast should appear
    await waitFor(() => {
      expect(screen.getByText('Repository added successfully')).toBeInTheDocument()
    })

    // Wait for query invalidation to complete (prevents act() warning)
    await waitFor(() => {
      expect(screen.queryByLabelText('Display Name')).not.toBeInTheDocument()
    })
  })

  it('shows error when API call fails', async () => {
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
    mockListRepositories([])
    mockCreateRepository(true) // shouldFail = true

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    // Wait for view to render
    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    // Open add dialog
    const addButton = screen.getByRole('button', { name: /add repository/i })
    await user.click(addButton)

    // Step 1: Provider selection
    await waitFor(
      () => {
        expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
      },
      { timeout: 10000 },
    )

    // Click Public Repository provider
    const publicRepoButton = screen.getByText(/public repository/i).closest('button')
    await user.click(publicRepoButton!)

    // Click NEXT to go to details step
    await user.click(screen.getByRole('button', { name: /next/i }))

    // Should go directly to manual details form
    await waitFor(() => {
      expect(screen.getByLabelText('Display Name')).toBeInTheDocument()
    })

    // Fill form
    await user.type(screen.getByLabelText('Display Name'), 'duplicate-repo')
    await user.type(screen.getByLabelText('Repository Git URL'), 'https://github.com/user/repo.git')

    // Submit
    const submitButton = screen.getByRole('button', { name: /onboard repository/i })
    await user.click(submitButton)

    // Dialog should close and view should remain (no new repo added)
    await waitFor(() => {
      expect(screen.queryByLabelText('Display Name')).not.toBeInTheDocument()
    })

    // Verify no new repository was added (still shows empty state)
    expect(screen.getByText('No repositories yet')).toBeInTheDocument()
  }, 15000)

  it('creates repository after team is selected post-render (closure bug test)', async () => {
    // This test reproduces the bug where teamId is captured in a closure
    // when useCreateRepository is first called, and doesn't update when
    // the user selects a team later.
    //
    // Scenario: User logs in (currentTeamId=null), views repos page,
    // then selects a team, then tries to create a repo.

    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: false,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])
    mockListRepositories([])
    mockTriggerIngestion('repo-1')
    mockCreateRepository()

    // Step 1: Authenticate WITHOUT teamId (simulates login before team selection)
    setAuthenticated({ userId: 'user-1' }) // No teamId!
    const { user } = renderWithRouter(['/repos'])

    // Wait for view to render
    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    // Step 2: User selects a team AFTER the view has rendered
    useAuthStore.getState().setCurrentTeamId('team-1')

    // Wait for repos query to re-run with new teamId
    await waitFor(() => {
      expect(screen.getByText('No repositories yet')).toBeInTheDocument()
    })

    // Step 3: Try to create a repository
    const addButton = screen.getByRole('button', { name: /add repository/i })
    await user.click(addButton)

    // Step 1: Provider selection
    await waitFor(() => {
      expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
    })

    // Click Public Repository provider
    const publicRepoButton = screen.getByText(/public repository/i).closest('button')
    await user.click(publicRepoButton!)

    // Click NEXT to go to details step
    await user.click(screen.getByRole('button', { name: /next/i }))

    // Should go directly to manual details form
    await waitFor(() => {
      expect(screen.getByLabelText('Display Name')).toBeInTheDocument()
    })

    // Fill form
    await user.type(screen.getByLabelText('Display Name'), 'my-new-repo')
    await user.type(screen.getByLabelText('Repository Git URL'), 'https://github.com/user/repo.git')

    // Submit
    const submitButton = screen.getByRole('button', { name: /onboard repository/i })
    await user.click(submitButton)

    // Success toast should appear - this will FAIL if teamId closure bug exists
    // The bug: mutationFn captures teamId at hook creation time, not execution time
    await waitFor(() => {
      expect(screen.getByText('Repository added successfully')).toBeInTheDocument()
    })

    // Wait for dialog to close (prevents act() warning from pending state updates)
    await waitFor(() => {
      expect(screen.queryByLabelText('Display Name')).not.toBeInTheDocument()
    })
  })

  it('uses current teamId at mutation execution time, not hook creation time', async () => {
    // This test specifically verifies that the mutation reads teamId from the store
    // at execution time, not from a closure captured at hook creation time.
    //
    // To prove this, we start WITH a team selected (so a buggy closure would capture 'team-1').
    // We then spy on useAuthStore.getState to return undefined for currentTeamId ONLY during
    // the mutation execution.
    // If the bug exists (closure captures 'team-1'), the mutation will proceed and NOT show the error.
    // If the fix is present (reads at execution time), it will see undefined and show "No team selected".

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
    mockListRepositories([])
    mockCreateRepository()

    // 1. Start WITH a team selected
    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { user } = renderWithRouter(['/repos'])

    // Wait for initial render
    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    // Open dialog and fill form
    const addButton = screen.getByRole('button', { name: /add repository/i })
    await user.click(addButton)

    await waitFor(() => {
      expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
    })

    const publicRepoButton = screen.getByText(/public repository/i).closest('button')
    await user.click(publicRepoButton!)

    await user.click(screen.getByRole('button', { name: /next/i }))

    await waitFor(() => {
      expect(screen.getByLabelText('Display Name')).toBeInTheDocument()
    })

    await user.type(screen.getByLabelText('Display Name'), 'my-new-repo')
    await user.type(screen.getByLabelText('Repository Git URL'), 'https://github.com/user/repo.git')

    // 2. Spy on getState to return undefined for currentTeamId ONLY during mutation execution
    const originalGetState = useAuthStore.getState
    vi.spyOn(useAuthStore, 'getState').mockImplementation(() => ({
      ...originalGetState(),
      currentTeamId: null,
    }))

    // 3. Submit
    const submitButton = screen.getByRole('button', { name: /onboard repository/i })
    await user.click(submitButton)

    // 4. Verify it fails with "No team selected" because it reads the CURRENT state
    await waitFor(() => {
      expect(screen.getByText('No team selected')).toBeInTheDocument()
    })

    vi.restoreAllMocks()
  })

  it('navigates wizard steps to onboard repository with newly created credentials', async () => {
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
    mockListRepositories([])
    mockTriggerIngestion('repo-1')
    mockCreateRepository()

    // Mock empty credentials list (GET only)
    addFetchHandler((url, options) => {
      if (
        url.includes('/credentials') &&
        !url.includes('available-repos') &&
        (options.method === 'GET' || !options.method)
      ) {
        return jsonResponse([])
      }
      return null
    })

    // Mock create credential
    addFetchHandler((url, options) => {
      if (url.includes('/credentials') && options.method === 'POST') {
        return jsonResponse(
          {
            createdAt: '2024-01-01T00:00:00Z',
            id: 'cred-123',
            name: 'GitLab Auth',
            type: 'GITLAB',
          },
          201,
        )
      }
      return null
    })

    // Mock available remote repos
    addFetchHandler((url) => {
      if (url.includes('/available-repos')) {
        return jsonResponse([
          {
            cloneUrl: 'https://gitlab.com/test/gitlab-demo.git',
            defaultBranch: 'main',
            name: 'gitlab-demo',
            sshUrl: 'git@gitlab.com:test/gitlab-demo.git',
          },
        ])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    // Wait for view
    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    // Click "Add Repository"
    await user.click(screen.getByRole('button', { name: /add repository/i }))

    // Step 1: Provider selection
    await waitFor(() => {
      expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
    })

    // Click GitLab provider
    const gitlabButton = screen.getByText('GitLab').closest('button')
    await user.click(gitlabButton!)

    // Click NEXT to go to auth step
    await user.click(screen.getByRole('button', { name: /next/i }))

    // Step 2: Auth step
    await waitFor(() => {
      expect(screen.getByText('Authenticate GITLAB')).toBeInTheDocument()
    })

    // Fill new credential form
    await waitFor(() => {
      expect(screen.getByLabelText('Personal/Group Access Token')).toBeInTheDocument()
    })
    await user.type(screen.getByLabelText('Credential Name'), 'Test GitLab Credential')
    await user.type(screen.getByLabelText('Personal/Group Access Token'), 'glpat-testtoken')

    // Click save
    await user.click(screen.getByRole('button', { name: /save & authenticate/i }))

    // Step 3: Repos step - should query remote repos and render them automatically
    await waitFor(() => {
      expect(screen.getByText('gitlab-demo')).toBeInTheDocument()
    })

    // Click the remote repo card to select it (checkbox toggle)
    await user.click(screen.getByText('gitlab-demo'))

    // Verify it's selected (should show checkbox checked)
    await waitFor(() => {
      expect(screen.getByText('1 selected')).toBeInTheDocument()
    })

    // Click the Onboard button
    await user.click(screen.getByRole('button', { name: /onboard 1 repo/i }))

    // Success toast should appear
    await waitFor(() => {
      expect(screen.getByText('Repository added successfully')).toBeInTheDocument()
    })
  })

  it('navigates wizard steps to onboard repository with newly created GitHub App credentials without requiring name', async () => {
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
    mockListRepositories([])
    mockCreateRepository()

    // Mock empty credentials list (GET only)
    addFetchHandler((url, options) => {
      if (
        url.includes('/credentials') &&
        !url.includes('available-repos') &&
        (options.method === 'GET' || !options.method)
      ) {
        return jsonResponse([])
      }
      return null
    })

    // Mock GitHub App Info fetch
    addFetchHandler((url) => {
      if (url.includes('/config/github-app')) {
        return jsonResponse({
          appId: 'client-123',
          appName: 'kratis-app',
          enabled: true,
          installationUrl: 'https://github.com/apps/kratis/installations/new',
        })
      }
      return null
    })

    // Mock Validate GitHub App Installation
    addFetchHandler((url) => {
      if (url.includes('/validate-github-app-installation')) {
        return jsonResponse({
          accountLogin: 'my-github-org',
          installationId: '12345678',
        })
      }
      return null
    })

    // Mock create credential
    const createdPayloadWrapper = { value: null as null | { name?: string } }
    addFetchHandler((url, options) => {
      if (url.includes('/credentials') && options.method === 'POST') {
        createdPayloadWrapper.value = JSON.parse(options.body as string)
        return jsonResponse(
          {
            createdAt: '2024-01-01T00:00:00Z',
            id: 'cred-app-123',
            name: 'my-github-org',
            type: 'GITHUB',
          },
          201,
        )
      }
      return null
    })

    // Mock available remote repos
    addFetchHandler((url) => {
      if (url.includes('/available-repos')) {
        return jsonResponse([
          {
            cloneUrl: 'https://github.com/my-github-org/demo-repo.git',
            defaultBranch: 'main',
            name: 'demo-repo',
            sshUrl: 'git@github.com:my-github-org/demo-repo.git',
          },
        ])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    // Wait for view
    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    // Click "Add Repository"
    await user.click(screen.getByRole('button', { name: /add repository/i }))

    // Step 1: Provider selection
    await waitFor(() => {
      expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
    })

    // Click GitHub provider
    const githubButton = screen.getByText('GitHub').closest('button')
    await user.click(githubButton!)

    // Click NEXT to go to auth step
    await user.click(screen.getByRole('button', { name: /next/i }))

    // Step 2: Auth step
    await waitFor(() => {
      expect(screen.getByText('Authenticate GITHUB')).toBeInTheDocument()
    })

    // Fill new credential form (Name is hidden for GitHub App, only Installation ID is rendered/entered)
    await waitFor(() => {
      expect(screen.getByLabelText('Installation ID')).toBeInTheDocument()
    })

    // Fill in Installation ID
    await user.type(screen.getByLabelText('Installation ID'), '12345678')

    // Click save & authenticate
    await user.click(screen.getByRole('button', { name: /save & authenticate/i }))

    // Step 3: Repos step - should query remote repos and render them automatically
    await waitFor(() => {
      expect(screen.getByText('demo-repo')).toBeInTheDocument()
    })

    // Verify name is automatically populated from organization account login
    expect(createdPayloadWrapper.value?.name).toBe('my-github-org')
  })

  it('navigates wizard steps to onboard repository with automatically generated SSH key', async () => {
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
    mockListRepositories([])
    mockCreateRepository()

    // Mock empty credentials list (GET only) and available repos
    addFetchHandler((url, options) => {
      if (
        url.includes('/credentials') &&
        !url.includes('available-repos') &&
        (options.method === 'GET' || !options.method)
      ) {
        return jsonResponse([])
      }
      if (url.includes('/available-repos')) {
        return jsonResponse([])
      }
      return null
    })

    // Mock generate SSH Key
    let generatedSshKeyPayload: null | { name: string } = null
    addFetchHandler((url, options) => {
      if (url.includes('/generate-ssh-key') && options.method === 'POST') {
        generatedSshKeyPayload = JSON.parse(options.body as string)
        return jsonResponse(
          {
            createdAt: '2024-01-01T00:00:00Z',
            id: 'cred-ssh-123',
            name: generatedSshKeyPayload?.name || 'SSH Key',
            publicKey: 'ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQ... kratis-key',
            type: 'SSH_KEY',
          },
          201,
        )
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    // Wait for view
    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    // Click "Add Repository"
    await user.click(screen.getByRole('button', { name: /add repository/i }))

    // Step 1: Provider selection
    await waitFor(() => {
      expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
    })

    // Click Custom Git provider
    const customGitButton = screen.getByText('Git with SSH key').closest('button')
    await user.click(customGitButton!)

    // Click NEXT to go to auth step
    await user.click(screen.getByRole('button', { name: /next/i }))

    // Step 2: Auth step
    await waitFor(() => {
      expect(screen.getByText('Authenticate Git with SSH Key')).toBeInTheDocument()
    })

    // Fill new credential form (defaults to ssh key)
    await waitFor(() => {
      expect(screen.getByText('Automatic SSH Generation')).toBeInTheDocument()
    })

    // Fill the Name field
    await user.type(screen.getByLabelText('Credential Name'), 'My Auto SSH Key')

    // Click save
    await user.click(screen.getByRole('button', { name: /save & authenticate/i }))

    // Should display the success screen with public key
    await waitFor(() => {
      expect(screen.getByText('SSH Key Pair Generated successfully!')).toBeInTheDocument()
      expect(screen.getByText(/kratis-key/)).toBeInTheDocument()
    })

    expect(generatedSshKeyPayload).toEqual({ name: 'My Auto SSH Key' })

    // Click the Copy Key button
    const copyButton = screen.getByRole('button', { name: /copy key/i })
    await user.click(copyButton)
    expect(await navigator.clipboard.readText()).toBe(
      'ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQ... kratis-key',
    )

    // Click the Continue button
    await user.click(screen.getByRole('button', { name: /i have added the deploy key, continue/i }))

    // Should skip repos step and go directly to manual details form
    await waitFor(() => {
      expect(screen.getByText('Verify configuration settings and sync')).toBeInTheDocument()
    })
  })

  it('onboards a public repository and ingests immediately by default', async () => {
    let onboardPayload: null | Record<string, unknown> = null
    let ingestTriggered = false

    // Register the ingest handler first so the create handler (which matches any
    // POST to /repositories) does not swallow the /ingest request.
    addFetchHandler((url, options) => {
      if (
        url === '/api/v1/teams/team-1/repositories/repo-new-id/ingest' &&
        options.method === 'POST'
      ) {
        ingestTriggered = true
        return jsonResponse({ batchId: 'batch-1', status: 'QUEUED' }, 202)
      }
      return null
    })

    addFetchHandler((url, options) => {
      if (url.includes('/repositories') && options.method === 'POST') {
        onboardPayload = JSON.parse(options.body as string)
        return jsonResponse(
          {
            branch: 'main',
            id: 'repo-new-id',
            name: 'public-repo',
            repositoryType: 'GENERIC',
            url: 'https://github.com/public/repo.git',
          },
          201,
        )
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    // Wait for view
    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    // Click "Add Repository"
    await user.click(screen.getByRole('button', { name: /add repository/i }))

    // Step 1: Provider selection
    await waitFor(() => {
      expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
    })

    // Click Public repository provider
    const publicButton = screen.getByText('Public repository').closest('button')
    await user.click(publicButton!)

    // Click NEXT to go directly to details step
    await user.click(screen.getByRole('button', { name: /next/i }))

    // Step 2: Details step - immediate ingestion is checked by default
    await waitFor(() => {
      expect(screen.getByLabelText('Display Name')).toBeInTheDocument()
    })
    const ingestCheckbox = screen.getByRole('checkbox', {
      name: /start ingesting immediately/i,
    })
    expect(ingestCheckbox).toBeChecked()

    // Fill form
    await user.type(screen.getByLabelText('Display Name'), 'public-repo')
    await user.type(
      screen.getByLabelText('Repository Git URL'),
      'https://github.com/public/repo.git',
    )

    // Submit
    const submitButton = screen.getByRole('button', { name: /onboard repository/i })
    await user.click(submitButton)

    // Success toast should appear
    await waitFor(() => {
      expect(screen.getByText('Repository added successfully')).toBeInTheDocument()
    })

    // Ingestion is triggered only after the repository creation resolves
    await waitFor(() => {
      expect(ingestTriggered).toBe(true)
    })

    expect(onboardPayload).toEqual({
      branch: 'main',
      name: 'public-repo',
      repositoryType: 'GENERIC',
      url: 'https://github.com/public/repo.git',
    })
  })

  it('does not trigger ingestion when immediate ingestion is unchecked', async () => {
    let ingestTriggered = false

    addFetchHandler((url, options) => {
      if (url.endsWith('/repositories/repo-unchecked/ingest') && options.method === 'POST') {
        ingestTriggered = true
        return jsonResponse({ batchId: 'batch-1', status: 'QUEUED' }, 202)
      }
      return null
    })

    addFetchHandler((url, options) => {
      if (url.includes('/repositories') && options.method === 'POST') {
        return jsonResponse(
          {
            branch: 'main',
            id: 'repo-unchecked',
            name: 'lazy-repo',
            repositoryType: 'GENERIC',
            url: 'https://github.com/public/lazy-repo.git',
          },
          201,
        )
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /add repository/i }))

    await waitFor(() => {
      expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
    })

    await user.click(screen.getByText('Public repository').closest('button')!)
    await user.click(screen.getByRole('button', { name: /next/i }))

    await waitFor(() => {
      expect(screen.getByLabelText('Display Name')).toBeInTheDocument()
    })

    // Uncheck immediate ingestion
    const ingestCheckbox = screen.getByRole('checkbox', {
      name: /start ingesting immediately/i,
    })
    await user.click(ingestCheckbox)
    expect(ingestCheckbox).not.toBeChecked()

    await user.type(screen.getByLabelText('Display Name'), 'lazy-repo')
    await user.type(
      screen.getByLabelText('Repository Git URL'),
      'https://github.com/public/lazy-repo.git',
    )

    await user.click(screen.getByRole('button', { name: /onboard repository/i }))

    await waitFor(() => {
      expect(screen.getByText('Repository added successfully')).toBeInTheDocument()
    })

    // Give any (incorrect) chained request a chance to fire
    await waitFor(() => {
      expect(screen.queryByLabelText('Display Name')).not.toBeInTheDocument()
    })
    expect(ingestTriggered).toBe(false)
  })

  it('deletes a repository successfully', async () => {
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

    let deleteCalled = false

    addFetchHandler((url, options) => {
      if (
        url === '/api/v1/teams/team-1/repositories' &&
        (!options.method || options.method === 'GET')
      ) {
        if (!deleteCalled) {
          return jsonResponse([
            {
              branch: 'main',
              createdAt: '2024-01-01T00:00:00Z',
              id: 'repo-delete-1',
              name: 'repo-to-delete',
              repositoryType: 'GENERIC',
              teamId: 'team-1',
              updatedAt: '2024-01-01T00:00:00Z',
              url: 'https://github.com/user/repo-to-delete.git',
            },
          ])
        }
        return jsonResponse([])
      }
      if (
        url === '/api/v1/teams/team-1/repositories/repo-delete-1' &&
        options.method === 'DELETE'
      ) {
        deleteCalled = true
        return new Response(null, { status: 200 })
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    // Wait for repo card to load
    await waitFor(() => {
      expect(screen.getByText('repo-to-delete')).toBeInTheDocument()
    })

    // Click the Trash icon button inside the repo-to-delete card
    const card = screen.getByText('repo-to-delete').closest('[data-slot="card"]')
    const trashButton = card?.querySelector('.text-destructive')
    await user.click(trashButton!)

    // Dialog should open
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Delete Repository' })).toBeInTheDocument()
    })

    // Click the delete button in the dialog
    const deleteButton = screen.getByRole('button', { name: 'Delete' })
    await user.click(deleteButton)

    // Success toast should appear
    await waitFor(() => {
      expect(screen.getByText('Repository deleted successfully')).toBeInTheDocument()
    })

    // Verify it is removed from the list
    await waitFor(() => {
      expect(screen.queryByText('repo-to-delete')).not.toBeInTheDocument()
    })

    expect(deleteCalled).toBe(true)
  })

  it('edits repository settings successfully', async () => {
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

    let updatePayload: null | UpdateRepositoryRequest = null

    addFetchHandler((url, options) => {
      if (
        url === '/api/v1/teams/team-1/repositories' &&
        (!options.method || options.method === 'GET')
      ) {
        if (!updatePayload) {
          return jsonResponse([
            {
              branch: 'main',
              createdAt: '2024-01-01T00:00:00Z',
              id: 'repo-edit-1',
              name: 'repo-to-edit',
              repositoryType: 'GENERIC',
              teamId: 'team-1',
              updatedAt: '2024-01-01T00:00:00Z',
              url: 'https://github.com/user/repo-to-edit.git',
            },
          ])
        }
        return jsonResponse([
          {
            branch: updatePayload.branch,
            createdAt: '2024-01-01T00:00:00Z',
            id: 'repo-edit-1',
            name: updatePayload.name,
            teamId: 'team-1',
            updatedAt: '2024-01-01T00:00:00Z',
            url: 'https://github.com/user/repo-to-edit.git',
          },
        ])
      }
      if (url === '/api/v1/teams/team-1/repositories/repo-edit-1' && options.method === 'PUT') {
        updatePayload = JSON.parse(options.body as string)
        return jsonResponse({
          branch: updatePayload!.branch,
          createdAt: '2024-01-01T00:00:00Z',
          id: 'repo-edit-1',
          name: updatePayload!.name,
          teamId: 'team-1',
          updatedAt: '2024-01-01T00:00:00Z',
          url: 'https://github.com/user/repo-to-edit.git',
        })
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('repo-to-edit')).toBeInTheDocument()
    })

    // Click the Settings icon button inside the repo card
    const card = screen.getByText('repo-to-edit').closest('[data-slot="card"]') as HTMLElement
    // Settings is the 4th button in the card
    const settingsButton = within(card).getAllByRole('button')[3]
    await user.click(settingsButton)

    // Dialog should open: "Edit Repository Settings"
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Edit Repository Settings' })).toBeInTheDocument()
    })

    // Modify branch and name
    const nameInput = screen.getByLabelText('Display Name')
    fireEvent.change(nameInput, { target: { value: 'repo-edited' } })

    const branchInput = screen.getByLabelText('Default Branch')
    fireEvent.change(branchInput, { target: { value: 'prod' } })

    // Submit
    const submitButton = screen.getByRole('button', { name: /update repository/i })
    await user.click(submitButton)

    // Success toast should appear
    await waitFor(() => {
      expect(screen.getByText('Repository updated successfully')).toBeInTheDocument()
    })

    // Verify it is updated in the list
    await waitFor(() => {
      expect(screen.getByText('repo-edited')).toBeInTheDocument()
      expect(screen.getByText('prod')).toBeInTheDocument()
    })

    expect(updatePayload).toEqual({
      branch: 'prod',
      name: 'repo-edited',
      repoId: 'repo-edit-1',
      repositoryType: 'GITHUB',
    })
  })

  it('triggers ingestion successfully', async () => {
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

    let ingestTriggered = false

    addFetchHandler((url, options) => {
      if (
        url === '/api/v1/teams/team-1/repositories' &&
        (!options.method || options.method === 'GET')
      ) {
        return jsonResponse([
          {
            branch: 'main',
            createdAt: '2024-01-01T00:00:00Z',
            id: 'repo-ingest-1',
            name: 'repo-to-ingest',
            repositoryType: 'GENERIC',
            teamId: 'team-1',
            updatedAt: '2024-01-01T00:00:00Z',
            url: 'https://github.com/user/repo-to-ingest.git',
          },
        ])
      }
      if (
        url === '/api/v1/teams/team-1/repositories/repo-ingest-1/ingest' &&
        options.method === 'POST'
      ) {
        ingestTriggered = true
        return jsonResponse({
          batchId: 'batch-1',
          status: 'QUEUED',
        })
      }
      if (url.includes('/ingestion-status')) {
        return jsonResponse({
          status: 'QUEUED',
        })
      }
      if (url.includes('/batches')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos/repo-ingest-1'])

    await waitFor(() => {
      expect(screen.getByText('repo-to-ingest')).toBeInTheDocument()
    })

    const ingestButton = screen.getByRole('button', { name: /ingest now/i })
    await user.click(ingestButton)

    // Success toast should appear
    await waitFor(() => {
      expect(screen.getByText('Ingestion started')).toBeInTheDocument()
    })

    expect(ingestTriggered).toBe(true)
  })

  it('cleans up invalid credential on GitLab validation failure', async () => {
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
    mockListRepositories([])

    // Mock empty credentials list (GET only)
    addFetchHandler((url, options) => {
      if (
        url.includes('/credentials') &&
        !url.includes('available-repos') &&
        (options.method === 'GET' || !options.method)
      ) {
        return jsonResponse([])
      }
      return null
    })

    // Mock create credential (POST)
    let credentialCreated = false
    addFetchHandler((url, options) => {
      if (url.includes('/credentials') && options.method === 'POST') {
        credentialCreated = true
        return jsonResponse(
          {
            createdAt: '2024-01-01T00:00:00Z',
            id: 'cred-failed-val',
            name: 'Invalid Gitlab Key',
            type: 'GITLAB',
          },
          201,
        )
      }
      return null
    })

    // Mock available-repos validation failure
    addFetchHandler((url) => {
      if (url.includes('/available-repos')) {
        return new Response(
          JSON.stringify({ message: 'Failed to connect to GitLab at custom domain' }),
          {
            headers: { 'Content-Type': 'application/json' },
            status: 400,
          },
        )
      }
      return null
    })

    // Mock delete credential (DELETE)
    let credentialDeleted = false
    addFetchHandler((url, options) => {
      if (url.includes('/credentials/cred-failed-val') && options.method === 'DELETE') {
        credentialDeleted = true
        return new Response(null, { status: 200 })
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    // Click "Add Repository"
    await user.click(screen.getByRole('button', { name: /add repository/i }))

    // Click GitLab
    const gitlabButton = screen.getByText('GitLab').closest('button')
    await user.click(gitlabButton!)
    await user.click(screen.getByRole('button', { name: /next/i }))

    // Fill new credential form
    await waitFor(() => {
      expect(screen.getByLabelText('Personal/Group Access Token')).toBeInTheDocument()
    })
    await user.type(screen.getByLabelText('Credential Name'), 'Invalid Gitlab Key')
    await user.type(screen.getByLabelText('Personal/Group Access Token'), 'glpat-badtoken')

    // Fill custom URL
    const customUrlInput = screen.getByLabelText('Self-Hosted GitLab URL (optional)')
    await user.type(customUrlInput, 'https://my-invalid-gitlab.local')

    // Submit
    await user.click(screen.getByRole('button', { name: /save & authenticate/i }))

    // Success toast should not appear, instead validation error toast
    await waitFor(() => {
      expect(screen.getByText('Failed to connect to GitLab at custom domain')).toBeInTheDocument()
    })

    // Verify deletion cleanup call was made
    expect(credentialCreated).toBe(true)
    expect(credentialDeleted).toBe(true)

    // Form should still be open on auth step
    expect(screen.getByLabelText('Personal/Group Access Token')).toBeInTheDocument()
  })

  it('shows validation error on GitHub App installation validation failure', async () => {
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
    mockListRepositories([])

    // Mock empty credentials list (GET only)
    addFetchHandler((url, options) => {
      if (
        url.includes('/credentials') &&
        !url.includes('available-repos') &&
        (options.method === 'GET' || !options.method)
      ) {
        return jsonResponse([])
      }
      return null
    })

    // Mock GitHub App Info fetch
    addFetchHandler((url) => {
      if (url.includes('/config/github-app')) {
        return jsonResponse({
          appId: 'client-123',
          appName: 'kratis-app',
          enabled: true,
          installationUrl: 'https://github.com/apps/kratis/installations/new',
        })
      }
      return null
    })

    // Mock Validate GitHub App Installation to fail
    let validateCalled = false
    addFetchHandler((url) => {
      if (url.includes('/validate-github-app-installation')) {
        validateCalled = true
        return new Response(JSON.stringify({ message: 'Invalid GitHub App Installation ID' }), {
          headers: { 'Content-Type': 'application/json' },
          status: 400,
        })
      }
      return null
    })

    // Spy on credentials POST (should not be called)
    let credentialCreated = false
    addFetchHandler((url, options) => {
      if (url.includes('/credentials') && options.method === 'POST') {
        credentialCreated = true
        return jsonResponse({}, 201)
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    // Click "Add Repository"
    await user.click(screen.getByRole('button', { name: /add repository/i }))

    // Click GitHub
    const githubButton = screen.getByText('GitHub').closest('button')
    await user.click(githubButton!)
    await user.click(screen.getByRole('button', { name: /next/i }))

    // Step 2: Auth step - Installation ID
    await waitFor(() => {
      expect(screen.getByLabelText('Installation ID')).toBeInTheDocument()
    })

    // Fill in Installation ID
    await user.type(screen.getByLabelText('Installation ID'), '999999')

    // Click save & authenticate
    await user.click(screen.getByRole('button', { name: /save & authenticate/i }))

    // Error should be shown
    await waitFor(() => {
      expect(screen.getByText('Invalid GitHub App Installation ID')).toBeInTheDocument()
    })

    expect(validateCalled).toBe(true)
    expect(credentialCreated).toBe(false)
  })

  it('does not offer GitHub App auth when no GitHub App is configured', async () => {
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
    mockListRepositories([])

    // Mock empty credentials list (GET only)
    addFetchHandler((url, options) => {
      if (
        url.includes('/credentials') &&
        !url.includes('available-repos') &&
        (options.method === 'GET' || !options.method)
      ) {
        return jsonResponse([])
      }
      return null
    })

    // GitHub App reports disabled
    addFetchHandler((url) => {
      if (url.includes('/config/github-app')) {
        return jsonResponse({
          appId: null,
          appName: null,
          enabled: false,
          installationUrl: null,
        })
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /add repository/i }))

    await waitFor(() => {
      expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
    })
    const githubButton = screen.getByText('GitHub').closest('button')
    await user.click(githubButton!)
    await user.click(screen.getByRole('button', { name: /next/i }))

    // Auth step defaults straight to a PAT credential form, with no GitHub App path
    await waitFor(() => {
      expect(screen.getByText('Authenticate GITHUB')).toBeInTheDocument()
      expect(screen.getByLabelText('Personal/Group Access Token')).toBeInTheDocument()
    })
    expect(screen.queryByLabelText('Installation ID')).not.toBeInTheDocument()
    expect(screen.queryByText('GitHub App (recommended)')).not.toBeInTheDocument()
  })

  it('updates repository list dynamically on team_entity_changed websocket message', async () => {
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

    let callCount = 0
    addFetchHandler((url) => {
      const match = url.match(/\/api\/v1\/teams\/[^/]+\/repositories$/)
      if (match) {
        callCount++
        if (callCount === 1) {
          return jsonResponse([
            {
              branch: 'main',
              createdAt: '2024-01-01T00:00:00Z',
              id: 'repo-1',
              name: 'frontend-app',
              repositoryType: 'GENERIC',
              teamId: 'team-1',
              updatedAt: '2024-01-01T00:00:00Z',
              url: 'https://github.com/user/frontend-app.git',
            },
          ])
        } else {
          return jsonResponse([
            {
              branch: 'main',
              createdAt: '2024-01-01T00:00:00Z',
              id: 'repo-1',
              name: 'frontend-app',
              repositoryType: 'GENERIC',
              teamId: 'team-1',
              updatedAt: '2024-01-01T00:00:00Z',
              url: 'https://github.com/user/frontend-app.git',
            },
            {
              branch: 'main',
              createdAt: '2024-01-01T00:00:00Z',
              id: 'repo-2',
              name: 'backend-app',
              repositoryType: 'GENERIC',
              teamId: 'team-1',
              updatedAt: '2024-01-01T00:00:00Z',
              url: 'https://github.com/user/backend-app.git',
            },
          ])
        }
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    renderWithRouter(['/repos'])

    // Wait for initial repo list to load
    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })
    expect(screen.queryByText('backend-app')).not.toBeInTheDocument()

    // Trigger team_entity_changed event via WebSocket
    const ws = setupConnected()
    ws.onmessage?.({
      data: JSON.stringify({
        result: {
          entity: 'REPOSITORIES',
          teamId: 'team-1',
          type: 'team_entity_changed',
        },
      }),
    })

    // Verify repository list dynamically reloads and shows the new repository
    await waitFor(() => {
      expect(screen.getByText('backend-app')).toBeInTheDocument()
    })
  })

  it('triggers teams refetch on user_entity_changed websocket message', async () => {
    let teamsCallCount = 0
    addFetchHandler((url) => {
      if (url === '/api/v1/teams' || url === '/api/v1/teams/') {
        teamsCallCount++
        return jsonResponse([
          {
            createdAt: '2024-01-01T00:00:00Z',
            id: 'team-1',
            isDefault: true,
            name: `Test Team (Fetch #${teamsCallCount})`,
            role: 'owner',
            tavilyApiKeyConfigured: false,
          },
        ])
      }
      return null
    })
    mockListRepositories([])

    setAuthenticated({ teamId: 'team-1' })
    renderWithRouter(['/repos'])

    // Wait for initial render and first teams fetch
    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })
    expect(teamsCallCount).toBeGreaterThanOrEqual(1)
    const initialCount = teamsCallCount

    // Send user_entity_changed to invalidate teams
    const ws = setupConnected()
    ws.onmessage?.({
      data: JSON.stringify({
        result: {
          entity: 'TEAMS',
          type: 'user_entity_changed',
          userId: 'user-1',
        },
      }),
    })

    // Verify it refetches teams
    await waitFor(() => {
      expect(teamsCallCount).toBeGreaterThan(initialCount)
    })
  })
})

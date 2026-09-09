import { beforeEach, describe, expect, it } from 'vitest'

import { createMockTeam } from '../support/test-factories'
import {
  addFetchHandler,
  jsonResponse,
  mockCreateCredential,
  mockCreateRepository,
  mockListCredentials,
  mockListRemoteRepositories,
  mockListRepositories,
  mockListTeams,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  fillFormAndSubmit,
  renderWithRouter,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
  waitForSuccessToast,
} from '../support/test-render'

describe('Repository Onboarding Flow (Flow A)', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  it('completes repository onboarding with new credentials', async () => {
    mockListTeams([createMockTeam()])
    mockListRepositories([])
    mockListCredentials([])
    mockCreateCredential()
    mockListRemoteRepositories([
      {
        cloneUrl: 'https://gitlab.com/test/gitlab-demo.git',
        defaultBranch: 'main',
        name: 'gitlab-demo',
        sshUrl: 'git@gitlab.com:test/gitlab-demo.git',
      },
    ])
    mockCreateRepository()

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos'])

    // 1. User navigates to `/repos` and clicks "Add Repository".
    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /add repository/i }))

    // 2. User selects a provider (e.g., "GitLab").
    await waitFor(() => {
      expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
    })
    const gitlabButton = screen.getByText('GitLab').closest('button')
    await user.click(gitlabButton!)
    await user.click(screen.getByRole('button', { name: /next/i }))

    // 3. User chooses to "Create New Credential" (instead of using an existing one).
    // Wait for auth step
    await waitFor(() => {
      expect(screen.getByText('Authenticate GITLAB')).toBeInTheDocument()
    })

    // 4 & 5. User submits the credential form (tests validation errors if fields are empty, then success).
    // First, test validation errors if fields are empty
    await user.click(screen.getByRole('button', { name: /save & authenticate/i }))
    await waitFor(() => {
      expect(screen.getByText('Name is required')).toBeInTheDocument()
    })

    // Now fill the form correctly
    await fillFormAndSubmit(
      user,
      {
        'Credential Name': 'Test GitLab Credential',
        'Personal/Group Access Token': 'glpat-testtoken',
      },
      'save & authenticate',
    )

    // 6. App fetches remote repositories using the new credential.
    // 7. User selects a remote repository from the list.
    await waitFor(() => {
      expect(screen.getByText('gitlab-demo')).toBeInTheDocument()
    })
    await user.click(screen.getByText('gitlab-demo'))

    // Verify it's selected
    await waitFor(() => {
      expect(screen.getByText('1 selected')).toBeInTheDocument()
    })

    // 8. User clicks "Onboard" to finalize.
    await user.click(screen.getByRole('button', { name: /onboard 1 repo/i }))

    // 9. Verify success toast appears and the repository is added to the list.
    await waitForSuccessToast('Repository added successfully')

    // Wait for dialog to close
    await waitFor(() => {
      expect(screen.queryByLabelText('Credential Name')).not.toBeInTheDocument()
    })
  })

  it('covers config-api fetchGitHubAppInfo success', async () => {
    addFetchHandler((url) => {
      if (url.includes('/api/v1/config/github-app')) {
        return jsonResponse({
          appId: 'test-client-id',
          appName: 'Test GitHub App',
          enabled: true,
          installationUrl: 'https://github.com/apps/test-slug/installations/new',
        })
      }
      return null
    })

    const { fetchGitHubAppInfo } = await import('@/lib/config-api')
    const info = await fetchGitHubAppInfo()
    expect(info.appId).toBe('test-client-id')
    expect(info.enabled).toBe(true)
  })

  it('covers config-api fetchGitHubAppInfo failure', async () => {
    addFetchHandler((url) => {
      if (url.includes('/api/v1/config/github-app')) {
        return jsonResponse({ message: 'GitHub App config error' }, 400)
      }
      return null
    })

    const { fetchGitHubAppInfo } = await import('@/lib/config-api')
    await expect(fetchGitHubAppInfo()).rejects.toThrow('GitHub App config error')
  })

  it('covers credential-api updateCredential and validateGitHubAppInstallation', async () => {
    // 1. Test updateCredential
    addFetchHandler((url, options) => {
      if (url.includes('/api/v1/teams/team-1/credentials/cred-1') && options.method === 'PUT') {
        const body = JSON.parse(options.body as string)
        expect(body.name).toBe('Updated Name')
        return jsonResponse({
          id: 'cred-1',
          name: 'Updated Name',
          type: 'github_app',
        })
      }
      return null
    })

    const { updateCredential, validateGitHubAppInstallation } = await import('@/lib/credential-api')
    const updated = await updateCredential('team-1', 'cred-1', {
      name: 'Updated Name',
      type: 'github_app',
    })
    expect(updated.name).toBe('Updated Name')

    // 2. Test validateGitHubAppInstallation
    addFetchHandler((url, options) => {
      if (
        url.includes('/api/v1/teams/team-1/credentials/validate-github-app-installation') &&
        options.method === 'POST'
      ) {
        expect(url).toContain('installationId=12345')
        return jsonResponse({
          accountLogin: 'test-account',
          installationId: '12345',
        })
      }
      return null
    })

    const validation = await validateGitHubAppInstallation('team-1', '12345')
    expect(validation.installationId).toBe('12345')
  })
})

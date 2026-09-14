import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { AuthMode, NewCredentialFormData, ProviderType } from '@/components/repos/repository-form-types'

import { CredentialForm } from '@/components/repos/credential-form'

import { renderWithProviders, screen } from '../../support/test-render'

describe('CredentialForm', () => {
  const noop = () => {}
  const noopSubmit = (_data: NewCredentialFormData) => {}
  const noopModeChange = (_mode: AuthMode) => {}

  function renderForm(selectedProvider: ProviderType, authMode: AuthMode = 'pat') {
    return renderWithProviders(
      <CredentialForm
        authMode={authMode}
        githubAppEnabled={false}
        isPending={false}
        onAuthModeChange={noopModeChange}
        onCancel={noop}
        onSubmit={noopSubmit}
        selectedProvider={selectedProvider}
      />,
    )
  }

  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it.each([
    [
      'github',
      /fine-grained PAT needs Contents: Read & write, Metadata: Read-only, and Pull requests: Read & write/i,
    ],
    ['gitlab', /needs the api and write_repository scopes/i],
    ['bitbucket', /needs Repositories: Read & write and Pull requests: Read & write/i],
    ['azure', /PAT needs Code: Read & write/i],
  ] as [ProviderType, RegExp][])('shows the %s permission hint in PAT mode', (provider, hint) => {
    renderForm(provider)

    expect(screen.getByText(hint)).toBeInTheDocument()
  })

  it('shows no permission hint for public repositories', () => {
    renderForm('public')

    expect(screen.queryByText(/needs/i)).not.toBeInTheDocument()
  })

  it('shows no permission hint in SSH key mode', () => {
    renderForm('ssh_key', 'ssh_key')

    expect(screen.queryByText(/needs/i)).not.toBeInTheDocument()
    expect(screen.getByText('Automatic SSH Generation')).toBeInTheDocument()
  })

  it('shows no PAT permission hint in GitHub App mode', () => {
    renderForm('github', 'github_app')

    expect(screen.queryByText(/fine-grained PAT/i)).not.toBeInTheDocument()
  })
})

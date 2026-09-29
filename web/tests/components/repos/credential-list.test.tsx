import { beforeEach, describe, expect, it, vi } from 'vitest'

import { CredentialList } from '@/components/repos/credential-list'

import {
  createMockCredential,
} from '../../support/test-factories'
import { renderWithProviders, screen, waitFor } from '../../support/test-render'

describe('CredentialList', () => {
  const noop = () => {}

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
      <CredentialList
        credentials={[]}
        isLoading
        onAddNew={noop}
        onSelect={noop}
        selectedCredentialId=""
      />,
    )

    expect(document.querySelector('.animate-spin')).toBeInTheDocument()
  })

  it('shows empty state when no credentials exist', () => {
    renderWithProviders(
      <CredentialList
        credentials={[]}
        isLoading={false}
        onAddNew={noop}
        onSelect={noop}
        selectedCredentialId=""
      />,
    )

    expect(screen.getByText('No saved credentials')).toBeInTheDocument()
    expect(
      screen.getByText(/Configure credentials to discover and pull private repositories/),
    ).toBeInTheDocument()
  })

  it('selects a credential and displays the checkmark', async () => {
    const onSelect = vi.fn()
    const credentials = [
      createMockCredential({ id: 'cred-1', name: 'GitLab PAT', type: 'GITLAB' }),
      createMockCredential({ id: 'cred-2', name: 'GitHub PAT', type: 'GITHUB' }),
    ]

    const { user } = renderWithProviders(
      <CredentialList
        credentials={credentials}
        isLoading={false}
        onAddNew={noop}
        onSelect={onSelect}
        selectedCredentialId=""
      />,
    )

    await user.click(screen.getByText('GitLab PAT'))

    await waitFor(() => {
      expect(onSelect).toHaveBeenCalledWith('cred-1')
    })
  })

  it('shows selected credential with checkmark', () => {
    const credentials = [
      createMockCredential({ id: 'cred-1', name: 'GitLab PAT', type: 'GITLAB' }),
      createMockCredential({ id: 'cred-2', name: 'GitHub PAT', type: 'GITHUB' }),
    ]

    renderWithProviders(
      <CredentialList
        credentials={credentials}
        isLoading={false}
        onAddNew={noop}
        onSelect={noop}
        selectedCredentialId="cred-2"
      />,
    )

    const selectedButton = screen.getByText('GitHub PAT').closest('button')
    expect(selectedButton?.querySelector('svg')).toBeInTheDocument()
  })

  it('displays public key for SSH key credentials', () => {
    const credentials = [
      createMockCredential({
        id: 'cred-ssh',
        name: 'Deploy Key',
        publicKey: 'ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCExampleKey',
        type: 'SSH_KEY',
      }),
    ]

    renderWithProviders(
      <CredentialList
        credentials={credentials}
        isLoading={false}
        onAddNew={noop}
        onSelect={noop}
        selectedCredentialId=""
      />,
    )

    expect(screen.getByText('Deploy Key')).toBeInTheDocument()
    expect(
      screen.getByText((content) => content.includes('Key:') && content.includes('ssh-rsa')),
    ).toBeInTheDocument()
    expect(screen.getByText('SSH KEY')).toBeInTheDocument()
  })

  it('calls onAddNew when clicking new credential button', async () => {
    const onAddNew = vi.fn()

    const { user } = renderWithProviders(
      <CredentialList
        credentials={[]}
        isLoading={false}
        onAddNew={onAddNew}
        onSelect={noop}
        selectedCredentialId=""
      />,
    )

    await user.click(screen.getByRole('button', { name: /new credential/i }))

    expect(onAddNew).toHaveBeenCalled()
  })
})

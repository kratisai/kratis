import { beforeEach, describe, expect, it } from 'vitest'

import {
  createMockTeam,
} from '../support/test-factories'
import {
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

describe('Credential List Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  async function openAuthStep(providerLabel: RegExp | string) {
    mockListTeams([createMockTeam()])
    mockListRepositories([])

    setAuthenticated({ teamId: 'team-1' })
    const result = renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('Repositories')).toBeInTheDocument()
    })

    await result.user.click(screen.getByRole('button', { name: /add repository/i }))

    await waitFor(() => {
      expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
    })

    await result.user.click(screen.getByRole('button', { name: providerLabel }))
    await result.user.click(screen.getByRole('button', { name: /next/i }))

    await waitFor(() => {
      expect(screen.getAllByText(/authenticate/i).length).toBeGreaterThan(0)
    })

    return result
  }

  it('selects an existing credential and shows checkmark', async () => {
    mockListCredentials([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'cred-1',
        name: 'GitLab PAT',
        type: 'GITLAB',
      },
    ])

    const { user } = await openAuthStep(/gitlab/i)

    await waitFor(() => {
      expect(screen.getByText('GitLab PAT')).toBeInTheDocument()
    })

    await user.click(screen.getByText('GitLab PAT'))

    await waitFor(() => {
      const button = screen.getByText('GitLab PAT').closest('button')
      expect(button?.querySelector('svg')).toBeInTheDocument()
    })
  })

  it('displays public key for SSH key credentials', async () => {
    mockListCredentials([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'cred-ssh',
        name: 'Deploy Key',
        publicKey: 'ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCExampleKey',
        type: 'SSH_KEY',
      },
    ])

    await openAuthStep(/git with ssh key/i)

    await waitFor(() => {
      expect(screen.getByText('Deploy Key')).toBeInTheDocument()
    })

    expect(
      screen.getByText((content) => content.includes('Key:') && content.includes('ssh-rsa')),
    ).toBeInTheDocument()
    expect(screen.getByText('SSH KEY')).toBeInTheDocument()
  })
})

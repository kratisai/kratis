import { fireEvent } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'

import {
  mockListTeams,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderIntegration,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'

describe('Layout Mobile Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  it('renders main layout and keeps mobile sidebar closed by default', async () => {
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

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { container } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByText('Test Team')).toBeInTheDocument()
    })

    // Mobile sidebar should not be rendered when closed
    expect(container.querySelector('aside.fixed')).not.toBeInTheDocument()
  })

  it('opens mobile sidebar when clicking the mobile menu button', async () => {
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

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { container } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByText('Test Team')).toBeInTheDocument()
    })

    // The mobile menu button is the one with the md:hidden class
    const menuButton = container.querySelector('button.md\\:hidden') as HTMLButtonElement
    fireEvent.click(menuButton)

    // Mobile sidebar should open as a fixed aside
    await waitFor(() => {
      expect(container.querySelector('aside.fixed')).toBeInTheDocument()
    })
  })

  it('closes mobile sidebar when clicking the close button', async () => {
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

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { container, user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByText('Test Team')).toBeInTheDocument()
    })

    const menuButton = container.querySelector('button.md\\:hidden') as HTMLButtonElement
    fireEvent.click(menuButton)

    await waitFor(() => {
      expect(container.querySelector('aside.fixed')).toBeInTheDocument()
    })

    // The mobile sidebar has an X button (first button inside the aside) that closes it
    const aside = container.querySelector('aside.fixed') as HTMLElement
    const closeButton = aside.querySelector('button') as HTMLButtonElement
    await user.click(closeButton)

    await waitFor(() => {
      expect(container.querySelector('aside.fixed')).not.toBeInTheDocument()
    })
  })

  it('closes mobile sidebar when clicking the backdrop', async () => {
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

    setAuthenticated({ teamId: 'team-1', userId: 'user-1' })
    const { container, user } = renderIntegration(['/settings'])

    await waitFor(() => {
      expect(screen.getByText('Test Team')).toBeInTheDocument()
    })

    const menuButton = container.querySelector('button.md\\:hidden') as HTMLButtonElement
    fireEvent.click(menuButton)

    await waitFor(() => {
      expect(container.querySelector('aside.fixed')).toBeInTheDocument()
    })

    // Backdrop is the div rendered before the aside
    const aside = container.querySelector('aside.fixed') as HTMLElement
    const backdrop = aside.previousElementSibling as HTMLElement
    await user.click(backdrop)

    await waitFor(() => {
      expect(container.querySelector('aside.fixed')).not.toBeInTheDocument()
    })
  })
})

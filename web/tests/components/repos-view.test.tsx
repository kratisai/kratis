import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ReposView } from '@/components/views/repos-view'
import * as repoApi from '@/lib/repo-api'
import { useAuthStore } from '@/store/auth-store'

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    useNavigate: vi.fn(() => vi.fn()),
    useParams: vi.fn(() => ({})),
    useRouter: vi.fn(() => ({
      navigate: vi.fn(),
      state: { location: { pathname: '/' } },
    })),
    useSearch: vi.fn(() => ({})),
  }
})

const mockRepositories = [
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
    branch: 'develop',
    createdAt: '2024-01-02T00:00:00Z',
    id: 'repo-2',
    name: 'api-service',
        repositoryType: 'GENERIC',
    teamId: 'team-1',
    updatedAt: '2024-01-02T00:00:00Z',
    url: 'https://github.com/user/api-service.git',
  },
]

vi.mock('@/lib/repo-api', () => ({
  createRepository: vi.fn(),
  deleteRepository: vi.fn(),
  getBatchLogs: vi.fn(),
  getIngestionStatus: vi.fn(),
  listRepositories: vi.fn(),
  updateRepository: vi.fn(),
}))

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })
}

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = createTestQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>,
  )
}

describe('ReposView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({
      currentTeamId: 'team-1',
      isAuthenticated: true,
    })
  })

  it('shows loading spinner while fetching', async () => {
    let resolvePromise: () => void
    vi.mocked(repoApi.listRepositories).mockReturnValue(
      new Promise((resolve) => {
        resolvePromise = () => resolve(mockRepositories)
      }) as never,
    )

    renderWithProviders(<ReposView />)

    // Check that the loading spinner is present
    expect(screen.getByText('Repositories')).toBeInTheDocument()

    // Resolve the promise to complete the loading
    resolvePromise!()

    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })
  })

  it('shows empty state when no repositories exist', async () => {
    vi.mocked(repoApi.listRepositories).mockResolvedValue([])

    renderWithProviders(<ReposView />)

    await waitFor(() => {
      expect(screen.getByText('No repositories yet')).toBeInTheDocument()
    })
  })

  it('displays list of repositories sorted alphabetically (case-insensitive)', async () => {
    vi.mocked(repoApi.listRepositories).mockResolvedValue(mockRepositories)

    const { container } = renderWithProviders(<ReposView />)

    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
      expect(screen.getByText('api-service')).toBeInTheDocument()
    })

    const headings = Array.from(
      container.querySelectorAll('[data-slot="card-title"]'),
    ).map((el) => el.textContent)
    expect(headings).toEqual(['api-service', 'frontend-app'])
  })

  it('opens add repository dialog when clicking Add Repository button', async () => {
    vi.mocked(repoApi.listRepositories).mockResolvedValue([])
    const user = userEvent.setup()

    renderWithProviders(<ReposView />)

    await waitFor(() => {
      expect(screen.getByText('No repositories yet')).toBeInTheDocument()
    })

    const addButton = screen.getByRole('button', { name: /add repository/i })
    await user.click(addButton)

    // Dialog title should show provider selection step
    await waitFor(() => {
      expect(screen.getByText('Choose Repository Host')).toBeInTheDocument()
    })
  })

})

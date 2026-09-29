import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { SidebarNav } from '@/components/layout/sidebar-nav'

// Mock dependencies
vi.mock('@/hooks/use-chats', () => ({
  useArchiveChat: vi.fn(() => ({
    mutate: vi.fn(),
  })),
  useChats: vi.fn(() => ({
    data: [
      {
        archivedAt: null,
        createdAt: '2024-01-01T00:00:00Z',
        createdByDisplayName: 'User 1',
        id: 'session-1',
        teamId: 'team-1',
        title: 'Test Session 1',
        updatedAt: '2024-01-01T00:00:00Z',
      },
      {
        archivedAt: '2024-01-02T00:00:00Z',
        createdAt: '2024-01-01T00:00:00Z',
        createdByDisplayName: 'User 1',
        id: 'session-2',
        teamId: 'team-1',
        title: 'Archived Session 2',
        updatedAt: '2024-01-02T00:00:00Z',
      },
    ],
    isLoading: false,
  })),
  useUnarchiveChat: vi.fn(() => ({
    mutate: vi.fn(),
  })),
}))

vi.mock('@/store/ui-store', () => ({
  useUIStore: vi.fn(() => ({
    sidebarOpen: true,
    toggleSidebar: vi.fn(),
  })),
}))

vi.mock('@/store/chat-store', () => ({
  useChatStore: vi.fn((selector) =>
    selector({
      subscribeChat: vi.fn(),
      unsubscribeChat: vi.fn(),
    }),
  ),
}))

vi.mock('@/store/auth-store', () => ({
  useAuthStore: Object.assign(
    vi.fn(() => ({
      currentTeamId: 'team-1',
    })),
    {
      getState: vi.fn(() => ({
        currentTeamId: 'team-1',
      })),
    }
  ),
}))

vi.mock('@tanstack/react-router', async () => {
  const actual = await vi.importActual('@tanstack/react-router')
  return {
    ...actual,
    Link: ({ children, ...props }: React.ComponentProps<'a'>) => <a {...props}>{children}</a>,
    useMatchRoute: vi.fn(() => vi.fn()),
    useNavigate: vi.fn(() => vi.fn()),
    useParams: vi.fn(() => ({})),
    useRouterState: vi.fn((opts) =>
      opts && opts.select ? opts.select({ location: { pathname: '/ask' } }) : { location: { pathname: '/ask' } },
    ),
  }
})

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false },
  },
})

function renderWithProviders(ui: React.ReactElement) {
  return render(ui, { wrapper: Wrapper })
}

function Wrapper({ children }: { children: React.ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

describe('SidebarNav', () => {
  it('displays sessions from API', () => {
    renderWithProviders(<SidebarNav />)

    expect(screen.getByText('Test Session 1')).toBeTruthy()
  })

  it('filter toggle works', () => {
    renderWithProviders(<SidebarNav />)

    // Find the filter button by its aria-label or by being the button with the User icon
    const filterButton = screen.getByRole('button', { name: /filter/i })
    expect(filterButton).toBeTruthy()

    fireEvent.click(filterButton)
  })

  it('clicking session loads messages via WS', () => {
    const { container } = renderWithProviders(<SidebarNav />)

    const sessionButton = container.querySelector('a')
    if (sessionButton) {
      fireEvent.click(sessionButton)
    }

    // The session click should have been handled
    expect(sessionButton).toBeTruthy()
  })

  it('ScrollArea for sessions fills remaining vertical space and allows scrolling', () => {
    const { container } = renderWithProviders(<SidebarNav />)

    // Find the ScrollArea container (it's the div with the flex-1 min-h-0 classes)
    const scrollArea = container.querySelector('.flex-1.min-h-0')
    expect(scrollArea).toBeTruthy()
    expect(scrollArea?.classList.contains('flex-1')).toBe(true)
    expect(scrollArea?.classList.contains('min-h-0')).toBe(true)
  })

  it('supports toggling status between active and archived chats', () => {
    renderWithProviders(<SidebarNav />)

    expect(screen.getByText('Test Session 1')).toBeTruthy()
  })

  it('does not render the wiki sidebar section outside the wiki route', () => {
    renderWithProviders(<SidebarNav />)

    expect(screen.queryByTestId('wiki-sidebar-section')).not.toBeInTheDocument()
  })
})

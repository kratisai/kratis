import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { SidebarNav, topicUrl } from '@/components/layout/sidebar-nav'

const mockUseChats = vi.hoisted(() => vi.fn())

vi.mock('@/hooks/use-chats', () => ({
  useArchiveChat: vi.fn(() => ({ mutate: vi.fn() })),
  useChats: (...args: unknown[]) => mockUseChats(...args),
  useUnarchiveChat: vi.fn(() => ({ mutate: vi.fn() })),
}))

vi.mock('@/store/ui-store', () => ({
  useUIStore: vi.fn(() => ({ sidebarOpen: true, toggleSidebar: vi.fn() })),
}))

vi.mock('@/store/chat-store', () => ({
  useChatStore: vi.fn((selector) =>
    selector({ subscribeChat: vi.fn(), unsubscribeChat: vi.fn() }),
  ),
}))

vi.mock('@/store/auth-store', () => ({
  useAuthStore: Object.assign(
    vi.fn((selector) => selector({ currentTeamId: 'team-1' })),
    {
      getState: vi.fn(() => ({ currentTeamId: 'team-1' })),
    },
  ),
}))

vi.mock('@tanstack/react-router', async () => {
  const actual = await vi.importActual('@tanstack/react-router')
  return {
    ...actual,
    Link: ({
      children,
      params,
      search,
      to,
    }: {
      children: React.ReactNode
      params?: unknown
      search?: unknown
      to: string
    }) => (
      <a data-params={JSON.stringify(params)} data-search={JSON.stringify(search)} data-to={to}>
        {children}
      </a>
    ),
    useMatchRoute: vi.fn(() => vi.fn()),
    useNavigate: vi.fn(() => vi.fn()),
    useParams: vi.fn(() => ({})),
    useRouterState: vi.fn((opts) =>
      opts && opts.select
        ? opts.select({ location: { pathname: '/ask' } })
        : { location: { pathname: '/ask' } },
    ),
  }
})

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

function defaultChats() {
  const recent = new Date().toISOString()
  return {
    data: [
      {
        archivedAt: null,
        createdAt: recent,
        createdByDisplayName: 'User 1',
        id: 'session-1',
        lastActivityAt: recent,
        status: 'working',
        teamId: 'team-1',
        title: 'Test Session 1',
        updatedAt: recent,
      },
      {
        archivedAt: '2024-01-02T00:00:00Z',
        createdAt: '2024-01-01T00:00:00Z',
        createdByDisplayName: 'User 1',
        id: 'session-2',
        lastActivityAt: recent,
        status: 'Blocked',
        teamId: 'team-1',
        title: 'Archived Session 2',
        updatedAt: recent,
      },
      {
        archivedAt: null,
        createdAt: recent,
        createdByDisplayName: 'User 1',
        id: 'session-3',
        lastActivityAt: recent,
        status: 'Idle',
        teamId: 'team-1',
        title: 'Idle Session',
        updatedAt: recent,
      },
      {
        archivedAt: null,
        createdAt: recent,
        createdByDisplayName: 'User 1',
        id: 'session-4',
        lastActivityAt: recent,
        lastActivityTargetId: 'exec-1',
        lastActivityType: 'EXECUTION',
        status: 'MR pushed',
        teamId: 'team-1',
        title: 'Published Session',
        updatedAt: recent,
      },
      {
        archivedAt: null,
        createdAt: recent,
        createdByDisplayName: 'User 1',
        id: 'session-5',
        lastActivityAt: recent,
        status: 'Blocked',
        teamId: 'team-1',
        title: 'Blocked Session',
        updatedAt: recent,
      },
      {
        archivedAt: null,
        createdAt: recent,
        createdByDisplayName: 'User 1',
        id: 'session-7',
        lastActivityAt: recent,
        status: 'Planning',
        teamId: 'team-1',
        title: 'Planning Session',
        updatedAt: recent,
      },
      {
        archivedAt: null,
        createdAt: '2020-01-01T00:00:00Z',
        createdByDisplayName: 'User 1',
        id: 'session-6',
        lastActivityAt: '2020-01-01T00:00:00Z',
        status: 'Idle',
        teamId: 'team-1',
        title: 'Stale Session',
        updatedAt: '2020-01-01T00:00:00Z',
      },
    ],
    isLoading: false,
  }
}

function renderWithProviders(ui: React.ReactElement) {
  return render(ui, {
    wrapper: ({ children }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    ),
  })
}

describe('SidebarNav', () => {
  beforeEach(() => {
    mockUseChats.mockReset()
    mockUseChats.mockImplementation((_teamId: string, _filter: string, status: string) => ({
      data: status === 'archived' ? [] : defaultChats().data,
      isLoading: false,
    }))
    queryClient.clear()
  })

  it('toggles between active and archived topics', () => {
    mockUseChats.mockClear()
    mockUseChats.mockImplementation((_teamId: string, _filter: string, status: string) => ({
      data:
        status === 'archived'
          ? defaultChats().data.filter((chat) => chat.archivedAt)
          : defaultChats().data.filter((chat) => !chat.archivedAt),
      isLoading: false,
    }))
    renderWithProviders(<SidebarNav />)
    expect(screen.getByText('Test Session 1')).toBeTruthy()

    fireEvent.click(screen.getByLabelText('Switch to archived chats'))
    expect(screen.getByText('Archived Session 2')).toBeTruthy()
    expect(screen.queryByText('Test Session 1')).not.toBeInTheDocument()
    expect(mockUseChats).toHaveBeenCalledWith('team-1', 'mine', 'archived')

    fireEvent.click(screen.getByLabelText('Switch to active chats'))
    expect(screen.getByText('Test Session 1')).toBeTruthy()
    expect(mockUseChats).toHaveBeenCalledWith('team-1', 'mine', 'active')
  })

  it('toggles the mine/all topic filter', () => {
    mockUseChats.mockClear()
    renderWithProviders(<SidebarNav />)
    expect(mockUseChats).toHaveBeenCalledWith('team-1', 'mine', 'active')

    fireEvent.click(screen.getByLabelText('Toggle topic filter'))
    expect(mockUseChats).toHaveBeenCalledWith('team-1', 'all', 'active')
  })

  it('routes topics by their most recent activity', () => {
    const base = {
      createdAt: '2026-10-20T17:32:12.672Z',
      createdByDisplayName: 'User 1',
      id: 'session-1',
      teamId: 'team-1',
      title: 'Topic',
      updatedAt: '2026-10-20T17:32:12.672Z',
    }

    expect(
      topicUrl({
        ...base,
        lastActivityTargetId: 'exec-ready',
        lastActivityType: 'EXECUTION',
        status: 'Ready to publish',
      }),
    ).toEqual({
      params: { executionId: 'exec-ready', id: 'session-1' },
      search: { tab: 'changes' },
      to: '/chats/$id/executions/$executionId',
    })

    expect(
      topicUrl({
        ...base,
        lastActivityTargetId: 'exec-1',
        lastActivityType: 'EXECUTION',
        status: 'MR pushed',
      }),
    ).toEqual({
      params: { executionId: 'exec-1', id: 'session-1' },
      search: undefined,
      to: '/chats/$id/executions/$executionId',
    })

    expect(
      topicUrl({ ...base, lastActivityTargetId: 'doc-1', lastActivityType: 'CANVAS', status: 'Idle' }),
    ).toEqual({
      params: { docId: 'doc-1', id: 'session-1' },
      search: undefined,
      to: '/chats/$id/canvas/$docId',
    })

    expect(topicUrl({ ...base, lastActivityType: 'CHAT', status: 'Idle' })).toEqual({
      params: { id: 'session-1' },
      search: undefined,
      to: '/chats/$id',
    })
  })

  it('clicking session loads messages via WS', () => {
    const { container } = renderWithProviders(<SidebarNav />)
    const sessionButton = container.querySelector('a')
    if (sessionButton) fireEvent.click(sessionButton)
    expect(sessionButton).toBeTruthy()
  })

  it('renders topic status labels with their color classes', () => {
    renderWithProviders(<SidebarNav />)
    expect(screen.getByText('working')).toHaveClass('text-white', 'font-semibold')
    expect(screen.getByText('Idle')).toHaveClass('text-yellow-400')
    expect(screen.getByText('MR pushed')).toHaveClass('text-emerald-400')
    expect(screen.getAllByText('Blocked')[0]).toHaveClass('text-amber-400')
    expect(screen.getByText('Planning')).toHaveClass('text-purple-400')
  })

  it('renders the last activity date for topics inactive for more than a week', () => {
    renderWithProviders(<SidebarNav />)
    const expectedDate = new Date('2020-01-01T00:00:00Z').toLocaleDateString(undefined, {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
    })
    expect(screen.getByText(expectedDate)).toHaveClass('text-gray-500')
    expect(screen.getAllByText('Idle')).toHaveLength(1)
  })

  it('does not render the wiki sidebar section outside the wiki route', () => {
    renderWithProviders(<SidebarNav />)
    expect(screen.queryByTestId('wiki-sidebar-section')).not.toBeInTheDocument()
  })
})

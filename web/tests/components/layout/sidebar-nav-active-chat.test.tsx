import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { ChatDto } from '@/lib/chat-api'

import { SidebarNav } from '@/components/layout/sidebar-nav'
import { useAuthStore } from '@/store/auth-store'

const { archiveMock, chatsMock, unarchiveMock } = vi.hoisted(() => ({
  archiveMock: { mutate: vi.fn() },
  chatsMock: vi.fn(),
  unarchiveMock: { mutate: vi.fn() },
}))

vi.mock('@/hooks/use-chats', () => ({
  useArchiveChat: () => archiveMock,
  useChats: () => ({ data: chatsMock() }),
  useUnarchiveChat: () => unarchiveMock,
}))

function chatDto(overrides: Partial<ChatDto>): ChatDto {
  return {
    createdAt: '2026-01-01T00:00:00.000Z',
    createdByDisplayName: 'Test User',
    id: 'chat-1',
    teamId: 'team-1',
    title: 'Chat One',
    updatedAt: '2026-01-02T00:00:00.000Z',
    ...overrides,
  }
}

const rootRoute = createRootRoute({
  component: () => (
    <>
      <SidebarNav />
      <Outlet />
    </>
  ),
})

const askRoute = createRoute({
  component: () => <div>ask</div>,
  getParentRoute: () => rootRoute,
  path: '/ask',
})

const reposRoute = createRoute({
  component: () => <div>repos</div>,
  getParentRoute: () => rootRoute,
  path: '/repos',
})

const agentsRoute = createRoute({
  component: () => <div>agents</div>,
  getParentRoute: () => rootRoute,
  path: '/agents',
})

const settingsRoute = createRoute({
  component: () => <div>settings</div>,
  getParentRoute: () => rootRoute,
  path: '/settings',
})

const chatRoute = createRoute({
  component: () => <Outlet />,
  getParentRoute: () => rootRoute,
  path: '/chats/$id',
})

const chatIndexRoute = createRoute({
  component: () => <div>chat index</div>,
  getParentRoute: () => chatRoute,
  path: '/',
})

const chatCanvasRoute = createRoute({
  component: () => <div>canvas</div>,
  getParentRoute: () => chatRoute,
  path: '/canvas',
})

const chatCanvasDocRoute = createRoute({
  component: () => <div>canvas doc</div>,
  getParentRoute: () => chatRoute,
  path: '/canvas/$docId',
})

const chatExecutionRoute = createRoute({
  component: () => <div>execution</div>,
  getParentRoute: () => chatRoute,
  path: '/executions/$executionId',
})

const routeTree = rootRoute.addChildren([
  askRoute,
  reposRoute,
  agentsRoute,
  settingsRoute,
  chatRoute.addChildren([chatIndexRoute, chatCanvasRoute, chatCanvasDocRoute, chatExecutionRoute]),
])

async function expectActiveChat(title: string) {
  const link = await screen.findByRole('link', { name: title })
  const container = link.parentElement
  expect(container?.className).toContain('bg-secondary')
}

async function expectInactiveChat(title: string) {
  const link = await screen.findByRole('link', { name: title })
  const container = link.parentElement
  expect(container?.className).not.toContain('bg-secondary')
}

function renderAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory({ initialEntries: [path] }),
    routeTree,
  })
  render(<RouterProvider router={router} />)
}

describe('SidebarNav', () => {
  beforeEach(() => {
    if (typeof globalThis.ResizeObserver === 'undefined') {
      globalThis.ResizeObserver = class ResizeObserver {
        disconnect() {}

        observe() {}

        unobserve() {}
      }
    }
    useAuthStore.setState({ currentTeamId: 'team-1' })
    chatsMock.mockReturnValue([
      chatDto({ id: 'chat-1', title: 'Chat One' }),
      chatDto({ id: 'chat-2', title: 'Chat Two', updatedAt: '2026-01-03T00:00:00.000Z' }),
    ])
  })

  afterEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({ currentTeamId: null })
  })

  it('highlights the active chat when viewing its canvas', async () => {
    renderAt('/chats/chat-1/canvas')

    await expectActiveChat('Chat One')
    await expectInactiveChat('Chat Two')
  })

  it('highlights the active chat when viewing a canvas document drill-down', async () => {
    renderAt('/chats/chat-1/canvas/doc-1')

    await expectActiveChat('Chat One')
    await expectInactiveChat('Chat Two')
  })

  it('highlights the active chat when viewing an execution drill-down', async () => {
    renderAt('/chats/chat-1/executions/exec-1')

    await expectActiveChat('Chat One')
    await expectInactiveChat('Chat Two')
  })

  it('highlights a different chat when navigating to its drill-down', async () => {
    renderAt('/chats/chat-2/executions/exec-2')

    await expectActiveChat('Chat Two')
    await expectInactiveChat('Chat One')
  })

  it('renders chat list and supports viewing archived chats', async () => {
    chatsMock.mockReturnValue([
      chatDto({
        archivedAt: '2026-01-02T00:00:00.000Z',
        id: 'chat-archived',
        title: 'Archived Chat',
      }),
    ])
    renderAt('/ask')

    const archivedChat = await screen.findByText('Archived Chat')
    expect(archivedChat).toBeInTheDocument()
  })
})

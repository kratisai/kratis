import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { MainLayout } from '@/components/layout/main-layout'

vi.mock('@/components/layout/header', () => ({ Header: () => <div data-testid="header" /> }))
vi.mock('@/components/layout/sidebar', () => ({ Sidebar: () => <div data-testid="sidebar" /> }))
vi.mock('@/components/layout/mobile-sidebar', () => ({
  MobileSidebar: () => <div data-testid="mobile-sidebar" />,
}))

const rootRoute = createRootRoute({
  component: () => <MainLayout />,
})

const indexRoute = createRoute({
  component: () => <div>index</div>,
  getParentRoute: () => rootRoute,
  path: '/',
})

const routeTree = rootRoute.addChildren([indexRoute])

function renderLayout() {
  const router = createRouter({
    history: createMemoryHistory({ initialEntries: ['/'] }),
    routeTree,
  })
  render(<RouterProvider router={router} />)
}

describe('MainLayout', () => {
  it('uses the dynamic viewport height on desktop so panes do not overflow mobile browsers', async () => {
    renderLayout()

    const root = await screen.findByTestId('main-layout')
    expect(root).toHaveClass('md:h-dvh')
    expect(root).not.toHaveClass('h-screen')
  })

  it('grows with content on mobile so the document (not an inner pane) scrolls', async () => {
    renderLayout()

    const root = await screen.findByTestId('main-layout')
    expect(root).toHaveClass('min-h-dvh')
    expect(root).not.toHaveClass('h-dvh')
  })

  it('lets the content column shrink below wide content on mobile', async () => {
    renderLayout()
    await screen.findByTestId('main-layout')

    const contentColumn = document.querySelector('main')?.parentElement
    expect(contentColumn).not.toBeNull()
    expect(contentColumn).toHaveClass('min-w-0')
    expect(contentColumn).toHaveClass('overflow-visible')
    expect(contentColumn).toHaveClass('md:overflow-hidden')
  })
})

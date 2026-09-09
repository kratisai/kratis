import { fireEvent, render } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { Sidebar } from '@/components/layout/sidebar'
import { useUIStore } from '@/store/ui-store'

vi.mock('@tanstack/react-router', async () => {
  const actual = await vi.importActual('@tanstack/react-router')
  return {
    ...actual,
    Link: ({ children, ...props }: React.ComponentProps<'a'>) => <a {...props}>{children}</a>,
    useMatchRoute: vi.fn(() => () => false),
    useNavigate: vi.fn(() => vi.fn()),
    useParams: vi.fn(() => ({})),
    useRouterState: vi.fn((opts) =>
      opts && opts.select ? opts.select({ location: { pathname: '/' } }) : { location: { pathname: '/' } },
    ),
  }
})

vi.mock('@/hooks/use-chats', () => ({
  useArchiveChat: vi.fn(() => ({ mutate: vi.fn() })),
  useChats: vi.fn(() => ({ data: [] })),
  useUnarchiveChat: vi.fn(() => ({ mutate: vi.fn() })),
}))

describe('Sidebar', () => {
  beforeEach(() => {
    useUIStore.setState({ sidebarOpen: false, sidebarViewport: 'narrow' })
    window.innerWidth = 800
  })

  it('animates width over 300ms while content stays at a fixed layout width', () => {
    const { container } = render(<Sidebar />)
    const aside = container.querySelector('aside')

    expect(aside).toHaveClass('transition-all')
    expect(aside).toHaveClass('duration-300')
    expect(aside).toHaveClass('overflow-hidden')

    const inner = aside?.firstElementChild as HTMLElement | null
    expect(inner).toHaveClass('w-full')

    fireEvent.mouseEnter(aside!)
    expect(inner).toHaveClass('w-64')

    fireEvent.mouseLeave(aside!)
    expect(inner).toHaveClass('w-full')
  })

  it('expands on hover when collapsed and keeps the rail out of flow', () => {
    const { container } = render(<Sidebar />)
    const aside = container.querySelector('aside')

    expect(container.querySelector('[aria-hidden="true"]')).toHaveClass('w-16')
    expect(aside).toHaveClass('fixed')
    expect(aside).toHaveClass('w-16')

    fireEvent.mouseEnter(aside!)

    expect(aside).toHaveClass('fixed')
    expect(aside).toHaveClass('w-64')

    fireEvent.mouseLeave(aside!)

    expect(aside).toHaveClass('fixed')
    expect(aside).toHaveClass('w-16')
  })

  it('applies the collapsed default on first mount below 900px', () => {
    window.innerWidth = 800
    useUIStore.setState({ sidebarOpen: true, sidebarViewport: 'wide' })

    const { container } = render(<Sidebar />)

    expect(useUIStore.getState().sidebarOpen).toBe(false)
    expect(container.querySelector('aside')).toHaveClass('w-16')

    window.innerWidth = 1024
  })

  it('collapses reactively when the viewport drops below 900px', () => {
    window.innerWidth = 1200
    useUIStore.setState({ sidebarOpen: true, sidebarViewport: 'wide' })

    render(<Sidebar />)

    window.innerWidth = 800
    fireEvent.resize(window)
    expect(useUIStore.getState().sidebarOpen).toBe(false)
  })
})

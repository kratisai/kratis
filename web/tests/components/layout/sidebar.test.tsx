import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { Sidebar } from '@/components/layout/sidebar'
import {
  SIDEBAR_WIDTH_DEFAULT,
  SIDEBAR_WIDTH_MAX,
  SIDEBAR_WIDTH_MIN,
  useUIStore,
} from '@/store/ui-store'

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
    useUIStore.setState({
      sidebarOpen: false,
      sidebarViewport: 'narrow',
      sidebarWidth: SIDEBAR_WIDTH_DEFAULT,
    })
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

  it('applies the stored width to the pinned sidebar in flow', () => {
    window.innerWidth = 1200
    useUIStore.setState({ sidebarOpen: true, sidebarViewport: 'wide', sidebarWidth: 320 })

    const { container } = render(<Sidebar />)
    const aside = container.querySelector('aside')

    expect(aside).not.toHaveClass('fixed')
    expect(aside).toHaveStyle({ width: '320px' })
    expect(screen.getByRole('separator', { name: 'Resize sidebar' })).toBeInTheDocument()
  })

  it('omits the resize handle while collapsed', () => {
    useUIStore.setState({ sidebarOpen: false, sidebarViewport: 'narrow' })

    render(<Sidebar />)

    expect(screen.queryByRole('separator', { name: 'Resize sidebar' })).not.toBeInTheDocument()
  })

  it('tracks the pointer while dragging and clamps to the allowed range', () => {
    window.innerWidth = 1200
    useUIStore.setState({ sidebarOpen: true, sidebarViewport: 'wide', sidebarWidth: 256 })

    render(<Sidebar />)
    const handle = screen.getByRole('separator', { name: 'Resize sidebar' })

    fireEvent.pointerDown(handle, { clientX: 256 })
    fireEvent.pointerMove(handle, { clientX: 320 })
    expect(useUIStore.getState().sidebarWidth).toBe(320)

    fireEvent.pointerMove(handle, { clientX: 900 })
    expect(useUIStore.getState().sidebarWidth).toBe(SIDEBAR_WIDTH_MAX)

    fireEvent.pointerMove(handle, { clientX: 40 })
    expect(useUIStore.getState().sidebarWidth).toBe(SIDEBAR_WIDTH_MIN)

    fireEvent.pointerUp(handle, { clientX: SIDEBAR_WIDTH_MIN })
  })

  it('suppresses the resize animation only while dragging', () => {
    window.innerWidth = 1200
    useUIStore.setState({ sidebarOpen: true, sidebarViewport: 'wide' })

    const { container } = render(<Sidebar />)
    const aside = container.querySelector('aside')!
    const handle = screen.getByRole('separator', { name: 'Resize sidebar' })

    expect(aside).toHaveClass('transition-all')

    fireEvent.pointerDown(handle, { clientX: 256 })
    expect(aside).toHaveClass('transition-none')
    expect(document.body.style.cursor).toBe('col-resize')
    expect(document.body.style.userSelect).toBe('none')

    fireEvent.pointerUp(handle, { clientX: 256 })
    expect(aside).toHaveClass('transition-all')
    expect(document.body.style.cursor).toBe('')
    expect(document.body.style.userSelect).toBe('')
  })

  it('resizes with arrow keys and resets via double-click', () => {
    window.innerWidth = 1200
    useUIStore.setState({ sidebarOpen: true, sidebarViewport: 'wide' })

    render(<Sidebar />)
    const handle = screen.getByRole('separator', { name: 'Resize sidebar' })

    fireEvent.keyDown(handle, { key: 'ArrowRight' })
    expect(useUIStore.getState().sidebarWidth).toBe(SIDEBAR_WIDTH_DEFAULT + 16)

    fireEvent.keyDown(handle, { key: 'ArrowLeft' })
    expect(useUIStore.getState().sidebarWidth).toBe(SIDEBAR_WIDTH_DEFAULT)

    fireEvent.keyDown(handle, { key: 'ArrowLeft' })
    expect(useUIStore.getState().sidebarWidth).toBe(SIDEBAR_WIDTH_DEFAULT - 16)

    fireEvent.dblClick(handle)
    expect(useUIStore.getState().sidebarWidth).toBe(SIDEBAR_WIDTH_DEFAULT)
  })
})

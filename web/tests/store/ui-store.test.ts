import { describe, expect, it } from 'vitest'

import {
  computeInitialSidebarOpen,
  SIDEBAR_WIDTH_DEFAULT,
  SIDEBAR_WIDTH_MAX,
  SIDEBAR_WIDTH_MIN,
  useUIStore,
} from '@/store/ui-store'

describe('ui-store sidebar defaults', () => {
  it('collapses the sidebar below the 900px threshold', () => {
    expect(computeInitialSidebarOpen(400)).toBe(false)
    expect(computeInitialSidebarOpen(899)).toBe(false)
  })

  it('expands the sidebar at 900px and above', () => {
    expect(computeInitialSidebarOpen(900)).toBe(true)
    expect(computeInitialSidebarOpen(1440)).toBe(true)
  })

  it('applies the viewport default when the 900px boundary is crossed', () => {
    useUIStore.setState({ sidebarOpen: true, sidebarViewport: 'wide' })

    useUIStore.getState().syncSidebarToViewport(800)
    expect(useUIStore.getState().sidebarOpen).toBe(false)
    expect(useUIStore.getState().sidebarViewport).toBe('narrow')

    useUIStore.getState().syncSidebarToViewport(1200)
    expect(useUIStore.getState().sidebarViewport).toBe('wide')
    expect(useUIStore.getState().sidebarOpen).toBe(true)
  })

  it('does not react to width changes within the same bucket', () => {
    useUIStore.setState({ sidebarOpen: true, sidebarViewport: 'wide' })

    useUIStore.getState().syncSidebarToViewport(1100)
    expect(useUIStore.getState().sidebarOpen).toBe(true)
  })

  it('respects a manual toggle until the viewport bucket changes', () => {
    useUIStore.setState({ sidebarOpen: true, sidebarViewport: 'wide' })

    useUIStore.getState().toggleSidebar()
    expect(useUIStore.getState().sidebarOpen).toBe(false)

    useUIStore.getState().syncSidebarToViewport(1100)
    expect(useUIStore.getState().sidebarOpen).toBe(false)

    useUIStore.getState().syncSidebarToViewport(700)
    expect(useUIStore.getState().sidebarOpen).toBe(false)
  })
})

describe('ui-store sidebar width', () => {
  it('starts at the default width', () => {
    expect(SIDEBAR_WIDTH_DEFAULT).toBe(256)
  })

  it('clamps manual width updates to the allowed range', () => {
    useUIStore.setState({ sidebarWidth: SIDEBAR_WIDTH_DEFAULT })

    useUIStore.getState().setSidebarWidth(1)
    expect(useUIStore.getState().sidebarWidth).toBe(SIDEBAR_WIDTH_MIN)

    useUIStore.getState().setSidebarWidth(9_999)
    expect(useUIStore.getState().sidebarWidth).toBe(SIDEBAR_WIDTH_MAX)

    useUIStore.getState().setSidebarWidth(320)
    expect(useUIStore.getState().sidebarWidth).toBe(320)
  })

  it('ignores non-finite width updates', () => {
    useUIStore.setState({ sidebarWidth: 320 })

    useUIStore.getState().setSidebarWidth(Number.NaN)
    expect(useUIStore.getState().sidebarWidth).toBe(320)
  })

  it('persists the sidebar width', () => {
    useUIStore.getState().setSidebarWidth(320)
    expect(localStorage.getItem('ui-store')).toContain('"sidebarWidth":320')
  })
})

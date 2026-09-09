import { renderHook } from '@testing-library/react'
import { act } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { useIsMobile } from '@/hooks/use-is-mobile'

function setViewportWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: width })
  act(() => {
    window.dispatchEvent(new Event('resize'))
  })
}

const originalInnerWidth = window.innerWidth

afterEach(() => {
  setViewportWidth(originalInnerWidth)
})

describe('useIsMobile', () => {
  it('returns false on a desktop viewport', () => {
    setViewportWidth(1024)
    const { result } = renderHook(() => useIsMobile())
    expect(result.current).toBe(false)
  })

  it('returns true on a viewport narrower than the md breakpoint', () => {
    setViewportWidth(375)
    const { result } = renderHook(() => useIsMobile())
    expect(result.current).toBe(true)
  })

  it('tracks viewport changes and cleans up its resize listener', () => {
    const addSpy = vi.spyOn(window, 'addEventListener')
    const removeSpy = vi.spyOn(window, 'removeEventListener')

    setViewportWidth(1024)
    const { result, unmount } = renderHook(() => useIsMobile())
    expect(result.current).toBe(false)

    setViewportWidth(767)
    expect(result.current).toBe(true)

    setViewportWidth(768)
    expect(result.current).toBe(false)

    expect(addSpy).toHaveBeenCalledWith('resize', expect.any(Function))
    unmount()
    expect(removeSpy).toHaveBeenCalledWith('resize', expect.any(Function))
  })

  it('honours a custom breakpoint', () => {
    setViewportWidth(800)
    const { result } = renderHook(() => useIsMobile(900))
    expect(result.current).toBe(true)
  })
})

import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useResetAfterDelay } from '@/hooks/use-reset-after-delay'

describe('useResetAfterDelay', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('holds the initial value without scheduling a reset', () => {
    const { result } = renderHook(() => useResetAfterDelay(false, false, 1500))
    expect(result.current[0]).toBe(false)

    act(() => {
      vi.advanceTimersByTime(5000)
    })
    expect(result.current[0]).toBe(false)
  })

  it('resets to the reset value after the delay', () => {
    const { result } = renderHook(() => useResetAfterDelay(false, false, 1500))

    act(() => {
      result.current[1](true)
    })
    expect(result.current[0]).toBe(true)

    act(() => {
      vi.advanceTimersByTime(1499)
    })
    expect(result.current[0]).toBe(true)

    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(result.current[0]).toBe(false)
  })

  it('restarts the countdown when a new value is set', () => {
    const { result } = renderHook(() => useResetAfterDelay<null | string>(null, null, 1500))

    act(() => {
      result.current[1]('first')
    })
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    act(() => {
      result.current[1]('second')
    })
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(result.current[0]).toBe('second')

    act(() => {
      vi.advanceTimersByTime(500)
    })
    expect(result.current[0]).toBeNull()
  })

  it('cancels the pending reset on unmount', () => {
    const { result, unmount } = renderHook(() => useResetAfterDelay('active', 'idle', 1500))

    act(() => {
      result.current[1]('still-active')
    })
    unmount()
    act(() => {
      vi.runAllTimers()
    })

    // No unhandled errors or state updates leak from the unmounted hook:
    // fake timers run cleanly and the suite passes with no stray callbacks.
    expect(vi.getTimerCount()).toBe(0)
  })
})

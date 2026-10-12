import { useCallback, useEffect, useState } from 'react'

/**
 * Holds a value that automatically resets after a delay. Setting a new value
 * restarts the countdown; unmount cancels it.
 */
export function useResetAfterDelay<T>(initialValue: T, resetValue: T, delayMs: number) {
  const [value, setValue] = useState(initialValue)

  useEffect(() => {
    if (Object.is(value, resetValue)) return
    const timer = setTimeout(() => setValue(resetValue), delayMs)
    return () => clearTimeout(timer)
  }, [value, resetValue, delayMs])

  const set = useCallback((next: T) => {
    setValue(next)
  }, [])

  return [value, set] as const
}

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { formatRelativeTime, formatSpend } from '@/lib/format'

describe('formatRelativeTime', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-15T12:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('returns "just now" for timestamps under a minute old', () => {
    expect(formatRelativeTime(new Date(Date.now() - 30 * 1000).toISOString())).toBe('just now')
  })

  it('returns minutes ago for timestamps under an hour old', () => {
    expect(formatRelativeTime(new Date(Date.now() - 5 * 60 * 1000).toISOString())).toBe('5m ago')
  })

  it('returns hours ago for timestamps under a day old', () => {
    expect(formatRelativeTime(new Date(Date.now() - 3 * 3600 * 1000).toISOString())).toBe('3h ago')
  })

  it('returns days ago for timestamps under a week old', () => {
    expect(formatRelativeTime(new Date(Date.now() - 2 * 24 * 3600 * 1000).toISOString())).toBe(
      '2d ago',
    )
  })

  it('returns a localized date for timestamps older than a week', () => {
    const old = new Date(Date.now() - 10 * 24 * 3600 * 1000)
    expect(formatRelativeTime(old.toISOString())).toBe(old.toLocaleDateString())
  })
})

describe('formatSpend', () => {
  it('formats a value as USD currency with two decimals', () => {
    expect(formatSpend(0.1234)).toBe('$0.12')
    expect(formatSpend(1.5)).toBe('$1.50')
  })

  it('returns an em dash for nullish values', () => {
    expect(formatSpend(null)).toBe('—')
    expect(formatSpend(undefined)).toBe('—')
  })
})

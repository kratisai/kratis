import { describe, expect, it } from 'vitest'

import { formatContextWindow } from '@/lib/context-window'

describe('formatContextWindow', () => {
  it('formats millions with at most one decimal', () => {
    expect(formatContextWindow(1048576)).toBe('1M')
    expect(formatContextWindow(2000000)).toBe('2M')
    expect(formatContextWindow(1500000)).toBe('1.5M')
  })

  it('formats thousands as whole K', () => {
    expect(formatContextWindow(128000)).toBe('128K')
    expect(formatContextWindow(131072)).toBe('131K')
    expect(formatContextWindow(8192)).toBe('8K')
  })

  it('leaves small windows as raw token counts', () => {
    expect(formatContextWindow(512)).toBe('512')
  })

  it('returns empty string for invalid values', () => {
    expect(formatContextWindow(0)).toBe('')
    expect(formatContextWindow(-1)).toBe('')
    expect(formatContextWindow(Number.NaN)).toBe('')
  })
})

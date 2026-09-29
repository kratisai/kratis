import { describe, expect, it } from 'vitest'

import { ACTIVITY_KINDS, toActivityKind } from '@/types/activity-kind'

describe('toActivityKind', () => {
  it('accepts every known kind verbatim', () => {
    for (const kind of ACTIVITY_KINDS) {
      expect(toActivityKind(kind)).toBe(kind)
    }
  })

  it('normalizes case', () => {
    expect(toActivityKind('READ')).toBe('read')
    expect(toActivityKind('Switch_Mode')).toBe('switch_mode')
  })

  it('maps the write alias', () => {
    expect(toActivityKind('write')).toBe('write')
  })

  it('falls back to other for unknown or missing kinds', () => {
    expect(toActivityKind('notebook_edit')).toBe('other')
    expect(toActivityKind('')).toBe('other')
    expect(toActivityKind(undefined)).toBe('other')
  })
})

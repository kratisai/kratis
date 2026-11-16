import { describe, expect, it } from 'vitest'

import { relationActionLabel } from '@/lib/relation-format'

describe('relationActionLabel', () => {
  it.each([
    ['CALLS', 'UPSTREAM', 'Called by'],
    ['CALLS', 'DOWNSTREAM', 'Calls'],
    ['CALLS', 'BIDIRECTIONAL', 'Calls and is called by'],
    ['CALLS', 'UNKNOWN', 'Has a call relationship with'],
    ['BUILDS_AGAINST', 'UPSTREAM', 'Provides a package to'],
    ['BUILDS_AGAINST', 'DOWNSTREAM', 'Builds against'],
    ['BUILDS_AGAINST', 'BIDIRECTIONAL', 'Has a package dependency with'],
    ['BUILDS_AGAINST', 'UNKNOWN', 'Has a package relationship with'],
    ['HOSTS', 'UPSTREAM', 'Hosts'],
    ['HOSTS', 'DOWNSTREAM', 'Runs on'],
    ['HOSTS', 'BIDIRECTIONAL', 'Has a hosting relationship with'],
    ['HOSTS', 'UNKNOWN', 'Has a hosting relationship with'],
    ['MESSAGES', 'BIDIRECTIONAL', 'Exchanges messages with'],
    ['SHARES_STATE', 'BIDIRECTIONAL', 'Shares state with'],
    ['PEER', 'BIDIRECTIONAL', 'Shares a domain with'],
  ] as const)('formats %s %s as "%s"', (nature, side, expected) => {
    expect(relationActionLabel(nature, side)).toBe(expected)
  })
})

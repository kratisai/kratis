import { afterEach, describe, expect, it, vi } from 'vitest'

import { uuid } from '@/lib/id'

const originalRandomUUID = crypto.randomUUID

function stubRandomUUID(impl: (() => string) | undefined) {
  Object.defineProperty(crypto, 'randomUUID', {
    configurable: true,
    value: impl,
  })
}

afterEach(() => {
  stubRandomUUID(originalRandomUUID)
  vi.restoreAllMocks()
})

describe('uuid', () => {
  it('uses crypto.randomUUID when it is available', () => {
    stubRandomUUID(() => '11111111-1111-4111-8111-111111111111')

    expect(uuid()).toBe('11111111-1111-4111-8111-111111111111')
  })

  it('falls back to a v4 UUID when crypto.randomUUID is unavailable', () => {
    stubRandomUUID(undefined)
    vi.spyOn(crypto, 'getRandomValues').mockImplementation((array) => {
      ;(array as Uint8Array).fill(0xab)
      return array
    })

    const id = uuid()

    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })
})

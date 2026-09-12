import { afterEach, describe, expect, it, vi } from 'vitest'

import { isEnvironmentFeaturesEnabled } from '@/lib/feature-flags'

describe('isEnvironmentFeaturesEnabled', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('is disabled when the env variable is unset', () => {
    expect(isEnvironmentFeaturesEnabled()).toBe(false)
  })

  it('is disabled for values other than "true"', () => {
    vi.stubEnv('VITE_ENABLE_ENVIRONMENT_FEATURES', '1')
    expect(isEnvironmentFeaturesEnabled()).toBe(false)

    vi.stubEnv('VITE_ENABLE_ENVIRONMENT_FEATURES', 'false')
    expect(isEnvironmentFeaturesEnabled()).toBe(false)
  })

  it('is enabled when the env variable is "true"', () => {
    vi.stubEnv('VITE_ENABLE_ENVIRONMENT_FEATURES', 'true')
    expect(isEnvironmentFeaturesEnabled()).toBe(true)
  })
})

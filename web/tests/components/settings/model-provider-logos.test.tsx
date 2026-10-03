import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { ProviderType } from '@/types/auth-types'

import { ModelProviderIcon, PROVIDER_BRAND } from '@/components/settings/model-provider-logos'

const PROVIDER_TYPES: ProviderType[] = [
  'ANTHROPIC',
  'AZURE_OPENAI',
  'BEDROCK',
  'DEEPSEEK',
  'GOOGLE',
  'GROQ',
  'KILO',
  'MISTRAL',
  'OLLAMA',
  'OPENAI',
  'OTHER',
]

describe('PROVIDER_BRAND', () => {
  it('covers every provider type', () => {
    expect(Object.keys(PROVIDER_BRAND).sort()).toEqual([...PROVIDER_TYPES].sort())
  })
})

describe('ModelProviderIcon', () => {
  it('renders the Kilo logo for KILO', () => {
    const { container } = render(<ModelProviderIcon providerType="KILO" />)
    const svg = container.querySelector('svg')
    expect(svg).toHaveAttribute('viewBox', '0 0 100 100')
  })

  it('renders an icon for every provider type', () => {
    for (const providerType of PROVIDER_TYPES) {
      const { container, unmount } = render(<ModelProviderIcon providerType={providerType} />)
      expect(container.querySelector('svg')).not.toBeNull()
      unmount()
    }
  })
})

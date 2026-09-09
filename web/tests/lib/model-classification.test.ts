import { describe, expect, it } from 'vitest'

import {
  classifyDiscoveredModels,
  looksLikeEmbeddingModel,
} from '@/lib/model-classification'

describe('model classification', () => {
  it('detects embedding models by name heuristics', () => {
    expect(looksLikeEmbeddingModel('text-embedding-ada-002')).toBe(true)
    expect(looksLikeEmbeddingModel('amazon.titan-embed-text-v2:0')).toBe(true)
    expect(looksLikeEmbeddingModel('bge-large-en')).toBe(true)
  })

  it('treats chat models as non-embedding', () => {
    expect(looksLikeEmbeddingModel('gpt-4o')).toBe(false)
    expect(looksLikeEmbeddingModel('claude-3-5-sonnet')).toBe(false)
    expect(looksLikeEmbeddingModel('')).toBe(false)
  })

  it('classifies a list of discovered models with kinds', () => {
    const models = classifyDiscoveredModels(['gpt-4', 'text-embedding-ada-002'])
    expect(models).toEqual([
      { kind: 'CHAT', modelName: 'gpt-4' },
      { kind: 'EMBEDDING', modelName: 'text-embedding-ada-002' },
    ])
  })
})

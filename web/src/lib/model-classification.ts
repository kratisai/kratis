import type { ModelEntryDto, ModelKind } from '@/types/auth-types'

export function classifyDiscoveredModels(modelNames: string[]): ModelEntryDto[] {
  return modelNames.map((modelName) => {
    const kind: ModelKind = looksLikeEmbeddingModel(modelName) ? 'EMBEDDING' : 'CHAT'
    return { kind, modelName }
  })
}

export function looksLikeEmbeddingModel(modelName: string): boolean {
  const lower = modelName.toLowerCase()
  return lower.includes('embed') || lower.includes('bge') || lower.includes('titan')
}

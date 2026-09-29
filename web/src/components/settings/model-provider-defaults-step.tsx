import type { ModelEntryDto, ModelKind, ModelProviderDto, TeamDto } from '@/types/auth-types'

import type { ModelProviderDefaultsState } from './use-model-provider-wizard'

const selectClass =
  'border-input bg-background placeholder:text-muted-foreground focus-visible:ring-ring flex h-9 w-full rounded-md border px-3 py-1 text-sm shadow-sm transition-colors focus-visible:ring-1 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50'

interface ModelProviderDefaultsStepProps {
  defaults: ModelProviderDefaultsState
  discoveredModels: ModelEntryDto[]
  onDefaultsChange: (defaults: ModelProviderDefaultsState) => void
  providerDisplayName: string
  providers: ModelProviderDto[]
  team: TeamDto | undefined
}

export function ModelProviderDefaultsStep({
  defaults,
  discoveredModels,
  onDefaultsChange,
  providerDisplayName,
  providers,
  team,
}: ModelProviderDefaultsStepProps) {
  const chatModels = discoveredModels.filter((model) => model.kind === 'CHAT')
  const embeddingModels = discoveredModels.filter((model) => model.kind === 'EMBEDDING')

  return (
    <div className="h-full min-h-0 flex-1 space-y-4 overflow-y-auto py-2">
      <p className="text-muted-foreground text-sm">
        Choose which models are used by default for ingestion and embeddings. You can change these
        anytime in team settings.
      </p>
      <DefaultSlot
        currentLabel={describeCurrentDefault(
          team?.ingestionProvider,
          team?.ingestionModel,
          providers,
        )}
        kind="CHAT"
        label="Ingestion model"
        mode={defaults.ingestion}
        model={defaults.ingestionModel}
        models={chatModels}
        onModeChange={(mode) => onDefaultsChange({ ...defaults, ingestion: mode })}
        onModelChange={(model) => onDefaultsChange({ ...defaults, ingestionModel: model })}
        providerDisplayName={providerDisplayName}
      />
      <DefaultSlot
        currentLabel={describeCurrentDefault(
          team?.embeddingProvider,
          team?.embeddingModel,
          providers,
        )}
        kind="EMBEDDING"
        label="Embedding model"
        mode={defaults.embedding}
        model={defaults.embeddingModel}
        models={embeddingModels}
        onModeChange={(mode) => onDefaultsChange({ ...defaults, embedding: mode })}
        onModelChange={(model) => onDefaultsChange({ ...defaults, embeddingModel: model })}
        providerDisplayName={providerDisplayName}
      />
    </div>
  )
}

function DefaultSlot({
  currentLabel,
  kind,
  label,
  mode,
  model,
  models,
  onModeChange,
  onModelChange,
  providerDisplayName,
}: {
  currentLabel: string
  kind: ModelKind
  label: string
  mode: 'keep' | 'use'
  model: string
  models: ModelEntryDto[]
  onModeChange: (mode: 'keep' | 'use') => void
  onModelChange: (model: string) => void
  providerDisplayName: string
}) {
  const hasModels = models.length > 0
  return (
    <div className="space-y-2 rounded-lg border p-4">
      <p className="text-sm font-medium">{label}</p>
      <label className="flex cursor-pointer items-center gap-2 text-sm">
        <input
          checked={mode === 'keep'}
          className="accent-primary"
          name={label}
          onChange={() => onModeChange('keep')}
          type="radio"
        />
        <span>Keep current default ({currentLabel})</span>
      </label>
      <label className="flex cursor-pointer items-center gap-2 text-sm">
        <input
          checked={mode === 'use'}
          className="accent-primary"
          disabled={!hasModels}
          name={label}
          onChange={() => onModeChange('use')}
          type="radio"
        />
        <span>
          Use {providerDisplayName}
          {!hasModels && ' (no matching models available)'}
        </span>
      </label>
      {mode === 'use' && hasModels && (
        <select
          aria-label={`${label} model`}
          className={selectClass}
          onChange={(e) => onModelChange(e.target.value)}
          value={model}
        >
          {models.map((entry) => (
            <option key={entry.modelName} value={entry.modelName}>
              {entry.modelName}
            </option>
          ))}
        </select>
      )}
      {!hasModels && (
        <p className="text-muted-foreground text-xs">
          {providerDisplayName} has no {kind === 'CHAT' ? 'chat' : 'embedding'} models selected.
        </p>
      )}
    </div>
  )
}

function describeCurrentDefault(
  providerId: string | undefined,
  model: string | undefined,
  providers: ModelProviderDto[],
): string {
  if (!providerId || !model) return 'Not set'
  const provider = providers.find((p) => p.id === providerId)
  return provider ? `${provider.displayName} — ${model}` : 'Not set'
}

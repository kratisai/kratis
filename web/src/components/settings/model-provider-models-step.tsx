import { CheckCircle2 } from 'lucide-react'
import { useMemo, useState } from 'react'

import type { ModelEntryDto, ModelKind } from '@/types/auth-types'

import { Badge } from '@/components/ui/badge'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'

interface ModelProviderModelsStepProps {
  discoveredModels: ModelEntryDto[]
  onSetAll: (kind: ModelKind, on: boolean) => void
  onToggle: (modelName: string) => void
  selectedModelNames: string[]
}

export function ModelProviderModelsStep({
  discoveredModels,
  onSetAll,
  onToggle,
  selectedModelNames,
}: ModelProviderModelsStepProps) {
  const chatModels = discoveredModels.filter((model) => model.kind === 'CHAT')
  const [filter, setFilter] = useState('')

  const selectedCount = chatModels.filter((model) =>
    selectedModelNames.includes(model.modelName),
  ).length

  const filtered = useMemo(() => {
    const query = filter.trim().toLowerCase()
    if (!query) return chatModels
    return chatModels.filter((model) => model.modelName.toLowerCase().includes(query))
  }, [chatModels, filter])

  if (chatModels.length === 0) {
    return (
      <div className="flex h-full min-h-0 flex-1 flex-col py-2">
        <p className="text-muted-foreground py-8 text-center text-sm">No chat models discovered.</p>
      </div>
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-1 flex-col space-y-3 py-2">
      <div className="flex min-h-0 flex-1 flex-col space-y-2 rounded-lg border p-3">
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="outline">{selectedCount} selected</Badge>
          <div className="ml-auto flex items-center gap-2">
            <button
              className="text-xs hover:underline"
              disabled={selectedCount === chatModels.length}
              onClick={() => onSetAll('CHAT', true)}
              type="button"
            >
              Select all
            </button>
            <button
              className="text-xs hover:underline"
              disabled={selectedCount === 0}
              onClick={() => onSetAll('CHAT', false)}
              type="button"
            >
              Clear
            </button>
          </div>
        </div>
        <Input
          aria-label="Filter chat models"
          className="h-8"
          onChange={(e) => setFilter(e.target.value)}
          placeholder="Filter models..."
          value={filter}
        />
        {filtered.length === 0 ? (
          <p className="text-muted-foreground py-4 text-center text-xs">No models match.</p>
        ) : (
          <ul className="min-h-0 flex-1 space-y-1 overflow-y-auto pr-1">
            {filtered.map((model) => {
              const checked = selectedModelNames.includes(model.modelName)
              return (
                <li key={model.modelName}>
                  <label className="hover:bg-accent/50 flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5">
                    <Checkbox
                      aria-label={model.modelName}
                      checked={checked}
                      onCheckedChange={() => onToggle(model.modelName)}
                    />
                    <span className="truncate text-sm">{model.modelName}</span>
                    {checked && <CheckCircle2 className="text-primary ml-auto h-4 w-4 shrink-0" />}
                  </label>
                </li>
              )
            })}
          </ul>
        )}
      </div>
      <p className="text-muted-foreground text-xs">
        Selected models are registered with the model router and become available to agents.
      </p>
    </div>
  )
}

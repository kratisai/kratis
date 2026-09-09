import { Cpu } from 'lucide-react'
import { useMemo, useState } from 'react'

import type { TeamDto } from '@/types/auth-types'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { useDiscoverModels, useModelProviders } from '@/hooks/use-model-providers'
import { useUpdateTeam } from '@/hooks/use-teams'

interface ModelDefaultsSettingsProps {
  isOwner: boolean
  team: TeamDto
}

export function ModelDefaultsSettings({ isOwner, team }: ModelDefaultsSettingsProps) {
  const { data: providers = [] } = useModelProviders()
  const updateTeam = useUpdateTeam()
  const [ingestionProvider, setIngestionProvider] = useState(team.ingestionProvider ?? '')
  const [ingestionModel, setIngestionModel] = useState(team.ingestionModel ?? '')
  const [embeddingProvider, setEmbeddingProvider] = useState(team.embeddingProvider ?? '')
  const [embeddingModel, setEmbeddingModel] = useState(team.embeddingModel ?? '')

  const { data: discoveredEmbeddingModels = [], isLoading: isLoadingEmbeddingModels } =
    useDiscoverModels(embeddingProvider || null)

  const embeddingModelOptions = useMemo(() => {
    return discoveredEmbeddingModels.filter((m) => m.kind === 'EMBEDDING')
  }, [discoveredEmbeddingModels])

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    updateTeam.mutate({
      embeddingModel: embeddingModel || undefined,
      embeddingProvider: embeddingProvider || undefined,
      ingestionModel: ingestionModel || undefined,
      ingestionProvider: ingestionProvider || undefined,
      teamId: team.id,
    })
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-2">
          <Cpu className="h-5 w-5" />
          <CardTitle>Default Models</CardTitle>
        </div>
        <CardDescription>
          Choose which provider and model to use for ingestion and embeddings
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={handleSubmit}>
          <div className="space-y-2">
            <Label htmlFor="ingestion-provider">Ingestion Provider</Label>
            <select
              className="border-input bg-background placeholder:text-muted-foreground focus-visible:ring-ring flex h-9 w-full rounded-md border px-3 py-1 text-sm shadow-sm transition-colors focus-visible:ring-1 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
              disabled={!isOwner}
              id="ingestion-provider"
              onChange={(e) => {
                const val = e.target.value
                setIngestionProvider(val)
                const selectedProv = providers.find((p) => p.id === val)
                const chatModels = selectedProv?.models?.filter((m) => m.kind === 'CHAT')
                if (chatModels && chatModels.length > 0) {
                  setIngestionModel(chatModels[0].modelName)
                } else {
                  setIngestionModel('')
                }
              }}
              value={ingestionProvider}
            >
              <option value="">(None - Default Active Provider)</option>
              {providers.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.displayName} ({p.providerType})
                </option>
              ))}
            </select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="ingestion-model">Ingestion Model</Label>
            <select
              className="border-input bg-background placeholder:text-muted-foreground focus-visible:ring-ring flex h-9 w-full rounded-md border px-3 py-1 text-sm shadow-sm transition-colors focus-visible:ring-1 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
              disabled={!isOwner}
              id="ingestion-model"
              onChange={(e) => setIngestionModel(e.target.value)}
              value={ingestionModel}
            >
              <option value="">Select a model</option>
              {providers
                .find((p) => p.id === ingestionProvider)
                ?.models?.filter((m) => m.kind === 'CHAT')
                .map((m) => (
                  <option key={m.modelName} value={m.modelName}>
                    {m.modelName}
                  </option>
                ))}
            </select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="embedding-provider">Embedding Provider</Label>
            <select
              className="border-input bg-background placeholder:text-muted-foreground focus-visible:ring-ring flex h-9 w-full rounded-md border px-3 py-1 text-sm shadow-sm transition-colors focus-visible:ring-1 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
              disabled={!isOwner}
              id="embedding-provider"
              onChange={(e) => {
                const val = e.target.value
                setEmbeddingProvider(val)
                const selectedProv = providers.find((p) => p.id === val)
                const embeddingModels = selectedProv?.models?.filter((m) => m.kind === 'EMBEDDING')
                if (embeddingModels && embeddingModels.length > 0) {
                  setEmbeddingModel(embeddingModels[0].modelName)
                } else {
                  setEmbeddingModel('')
                }
              }}
              value={embeddingProvider}
            >
              <option value="">(None - Default Active Provider)</option>
              {providers.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.displayName} ({p.providerType})
                </option>
              ))}
            </select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="embedding-model">Embedding Model</Label>
            <select
              className="border-input bg-background placeholder:text-muted-foreground focus-visible:ring-ring flex h-9 w-full rounded-md border px-3 py-1 text-sm shadow-sm transition-colors focus-visible:ring-1 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
              disabled={!isOwner}
              id="embedding-model"
              onChange={(e) => setEmbeddingModel(e.target.value)}
              value={embeddingModel}
            >
              <option value="">Select a model</option>
              {isLoadingEmbeddingModels ? (
                <option disabled>Loading models...</option>
              ) : (
                embeddingModelOptions.map((m) => (
                  <option key={m.modelName} value={m.modelName}>
                    {m.modelName}
                  </option>
                ))
              )}
            </select>
          </div>
          {isOwner && (
            <Button disabled={updateTeam.isPending} type="submit">
              {updateTeam.isPending ? 'Saving...' : 'Save Defaults'}
            </Button>
          )}
        </form>
      </CardContent>
    </Card>
  )
}

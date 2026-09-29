import { Edit2, Plus, Trash2 } from 'lucide-react'
import { useRef, useState } from 'react'

import type { ModelProviderDto, TeamDto } from '@/types/auth-types'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {
  useCreateModelProvider,
  useDeleteModelProvider,
  useModelProviders,
  useUpdateModelProvider,
} from '@/hooks/use-model-providers'
import { useUpdateTeam } from '@/hooks/use-teams'
import { useAuthStore } from '@/store/auth-store'

import {
  type ModelProviderSubmitPayload,
  ModelProviderWizardDialog,
} from './model-provider-wizard-dialog'

interface ModelProviderListProps {
  isOwner: boolean
  team: TeamDto
}

export function ModelProviderList({ isOwner, team }: ModelProviderListProps) {
  const { data: providers, isLoading } = useModelProviders()
  const createProvider = useCreateModelProvider()
  const updateProvider = useUpdateModelProvider()
  const deleteProvider = useDeleteModelProvider()
  const updateTeam = useUpdateTeam()
  const teamId = useAuthStore().currentTeamId ?? team.id

  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingProvider, setEditingProvider] = useState<ModelProviderDto | undefined>()
  const savedProviderRef = useRef<ModelProviderDto | null>(null)

  const handleAdd = () => {
    setEditingProvider(undefined)
    setDialogOpen(true)
  }

  const handleEdit = (provider: ModelProviderDto) => {
    setEditingProvider(provider)
    setDialogOpen(true)
  }

  const handleDelete = (providerId: string, providerName: string) => {
    if (window.confirm(`Are you sure you want to delete "${providerName}"?`)) {
      deleteProvider.mutate(providerId)
    }
  }

  const handleSubmit = async (
    payload: ModelProviderSubmitPayload,
    retryDefaultsOnly = false,
  ): Promise<boolean> => {
    if (!retryDefaultsOnly) {
      if (payload.mode === 'create') {
        savedProviderRef.current = await createProvider.mutateAsync(payload.data)
      } else {
        savedProviderRef.current = await updateProvider.mutateAsync({
          data: payload.data,
          providerId: payload.providerId,
        })
      }
    }

    if (payload.defaults) {
      const savedProvider = savedProviderRef.current
      if (!savedProvider) return false
      try {
        await updateTeam.mutateAsync({
          embeddingModel: payload.defaults.embedding?.model,
          embeddingProvider: payload.defaults.embedding ? savedProvider.id : undefined,
          ingestionModel: payload.defaults.ingestion?.model,
          ingestionProvider: payload.defaults.ingestion ? savedProvider.id : undefined,
          teamId,
        })
        return true
      } catch {
        return false
      }
    }
    return true
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <div className="flex flex-col space-y-1.5">
            <CardTitle>Model Providers</CardTitle>
            <CardDescription>Configure AI model providers for this team</CardDescription>
          </div>
          {isOwner && (
            <Button onClick={handleAdd} size="sm">
              <Plus className="mr-2 h-4 w-4" />
              Add Provider
            </Button>
          )}
        </div>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <p className="text-muted-foreground text-sm">Loading providers...</p>
        ) : !providers || providers.length === 0 ? (
          <div className="flex h-24 items-center justify-center rounded-md border border-dashed">
            <p className="text-muted-foreground text-sm">No model providers configured</p>
          </div>
        ) : (
          <>
            <div className="hidden md:block">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Name</TableHead>
                    <TableHead>Type</TableHead>
                    <TableHead>Base URL</TableHead>
                    {isOwner && <TableHead className="w-[100px]">Actions</TableHead>}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {providers.map((provider) => (
                    <TableRow key={provider.id}>
                      <TableCell className="font-medium">{provider.displayName}</TableCell>
                      <TableCell>{provider.providerType}</TableCell>
                      <TableCell className="text-muted-foreground max-w-[200px] truncate">
                        {provider.baseUrl || 'Default'}
                      </TableCell>
                      {isOwner && (
                        <TableCell>
                          <div className="flex items-center gap-2">
                            <Button
                              aria-label={`Edit ${provider.displayName}`}
                              onClick={() => handleEdit(provider)}
                              size="icon"
                              variant="ghost"
                            >
                              <Edit2 className="h-4 w-4" />
                            </Button>
                            <Button
                              aria-label={`Delete ${provider.displayName}`}
                              className="text-destructive"
                              onClick={() => handleDelete(provider.id, provider.displayName)}
                              size="icon"
                              variant="ghost"
                            >
                              <Trash2 className="h-4 w-4" />
                            </Button>
                          </div>
                        </TableCell>
                      )}
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            <div className="space-y-2 md:hidden" data-testid="model-provider-cards">
              {providers.map((provider) => (
                <div className="rounded-lg border p-3" key={provider.id}>
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium">{provider.displayName}</p>
                      <p className="text-muted-foreground text-xs uppercase">
                        {provider.providerType}
                      </p>
                    </div>
                    {isOwner && (
                      <div className="flex shrink-0 gap-1">
                        <Button
                          aria-label={`Edit ${provider.displayName}`}
                          onClick={() => handleEdit(provider)}
                          size="icon"
                          variant="ghost"
                        >
                          <Edit2 className="h-4 w-4" />
                        </Button>
                        <Button
                          aria-label={`Delete ${provider.displayName}`}
                          className="text-destructive"
                          onClick={() => handleDelete(provider.id, provider.displayName)}
                          size="icon"
                          variant="ghost"
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                    )}
                  </div>
                  <p className="text-muted-foreground mt-1.5 truncate text-sm">
                    {provider.baseUrl || 'Default'}
                  </p>
                </div>
              ))}
            </div>
          </>
        )}
      </CardContent>
      <ModelProviderWizardDialog
        onOpenChange={setDialogOpen}
        onSubmit={handleSubmit}
        open={dialogOpen}
        provider={editingProvider}
        team={team}
      />
    </Card>
  )
}

import { Edit2, Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'

import type {
  CreateProviderData,
  EnvironmentProviderDto,
  UpdateProviderData,
} from '@/lib/provider-api'

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
  useCreateProvider,
  useDeleteProvider,
  useProviders,
  useUpdateProvider,
} from '@/hooks/use-providers'

import type { FormValues } from './provider-form-dialog'

import { ProviderFormDialog } from './provider-form-dialog'

interface ProviderListProps {
  isOwner: boolean
}

export function ProviderList({ isOwner }: ProviderListProps) {
  const { data: providers, isLoading } = useProviders()
  const createProvider = useCreateProvider()
  const updateProvider = useUpdateProvider()
  const deleteProvider = useDeleteProvider()

  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingProvider, setEditingProvider] = useState<EnvironmentProviderDto | undefined>()

  const handleAdd = () => {
    setEditingProvider(undefined)
    setDialogOpen(true)
  }

  const handleEdit = (provider: EnvironmentProviderDto) => {
    setEditingProvider(provider)
    setDialogOpen(true)
  }

  const handleSubmit = (data: FormValues) => {
    if (editingProvider) {
      const updateData: UpdateProviderData = {
        dockerImage: data.dockerImage,
        name: data.name,
      }
      updateProvider.mutate({
        data: updateData,
        providerId: editingProvider.id,
      })
    } else {
      const createData: CreateProviderData = {
        dockerImage: data.dockerImage,
        name: data.name,
      }
      createProvider.mutate(createData)
    }
  }

  const handleDelete = (providerId: string, providerName: string) => {
    if (window.confirm(`Are you sure you want to delete "${providerName}"?`)) {
      deleteProvider.mutate(providerId)
    }
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <div className="flex flex-col space-y-1.5">
            <CardTitle>Environment Providers</CardTitle>
            <CardDescription>
              Configure environment providers (e.g., Docker, Workspace) for this team
            </CardDescription>
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
            <p className="text-muted-foreground text-sm">No environment providers configured</p>
          </div>
        ) : (
          <>
            <div className="hidden md:block">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Name</TableHead>
                    <TableHead>Docker Image</TableHead>
                    {isOwner && <TableHead className="w-25">Actions</TableHead>}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {providers.map((provider) => (
                    <TableRow key={provider.id}>
                      <TableCell className="font-medium">{provider.name}</TableCell>
                      <TableCell className="text-muted-foreground max-w-50 truncate">
                        {provider.dockerImage || '-'}
                      </TableCell>
                      {isOwner && (
                        <TableCell>
                          <div className="flex items-center gap-2">
                            <Button
                              aria-label={`Edit ${provider.name}`}
                              onClick={() => handleEdit(provider)}
                              size="icon"
                              variant="ghost"
                            >
                              <Edit2 className="h-4 w-4" />
                            </Button>
                            <Button
                              aria-label={`Delete ${provider.name}`}
                              className="text-destructive"
                              onClick={() => handleDelete(provider.id, provider.name)}
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
            <div className="space-y-2 md:hidden" data-testid="environment-provider-cards">
              {providers.map((provider) => (
                <div className="rounded-lg border p-3" key={provider.id}>
                  <div className="flex items-start justify-between gap-2">
                    <p className="min-w-0 truncate text-sm font-medium">{provider.name}</p>
                    {isOwner && (
                      <div className="flex shrink-0 gap-1">
                        <Button
                          aria-label={`Edit ${provider.name}`}
                          onClick={() => handleEdit(provider)}
                          size="icon"
                          variant="ghost"
                        >
                          <Edit2 className="h-4 w-4" />
                        </Button>
                        <Button
                          aria-label={`Delete ${provider.name}`}
                          className="text-destructive"
                          onClick={() => handleDelete(provider.id, provider.name)}
                          size="icon"
                          variant="ghost"
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                    )}
                  </div>
                  <p className="text-muted-foreground mt-1.5 truncate text-sm">
                    {provider.dockerImage || '-'}
                  </p>
                </div>
              ))}
            </div>
          </>
        )}
      </CardContent>
      <ProviderFormDialog
        onOpenChange={setDialogOpen}
        onSubmit={handleSubmit}
        open={dialogOpen}
        provider={editingProvider}
      />
    </Card>
  )
}

import { Copy, Plus, Square, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import type { CreateEnvironmentResponse, ExecutionEnvironmentDto } from '@/lib/environment-api'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {
  useCreateConnector,
  useDeleteEnvironment,
  useEnvironments,
  useTerminateEnvironment,
} from '@/hooks/use-environments'
import { copyToClipboard } from '@/lib/clipboard'
import { isEnvironmentFeaturesEnabled } from '@/lib/feature-flags'

interface EnvironmentListProps {
  isOwner: boolean
}

export function EnvironmentList({ isOwner }: EnvironmentListProps) {
  const { data: environments, isLoading } = useEnvironments()
  const deleteEnvironment = useDeleteEnvironment()
  const terminateEnvironment = useTerminateEnvironment()
  const createConnector = useCreateConnector()
  const environmentFeaturesEnabled = isEnvironmentFeaturesEnabled()

  const [isDialogOpen, setIsDialogOpen] = useState(false)
  const [connectorName, setConnectorName] = useState('')
  const [createdConnector, setCreatedConnector] = useState<CreateEnvironmentResponse | null>(null)

  const handleDelete = (envId: string, envName: string) => {
    if (window.confirm(`Are you sure you want to delete "${envName}"?`)) {
      deleteEnvironment.mutate(envId)
    }
  }

  const handleTerminate = (envId: string, envName: string) => {
    if (window.confirm(`Are you sure you want to terminate "${envName}"?`)) {
      terminateEnvironment.mutate(envId)
    }
  }

  const isRunningSandbox = (env: ExecutionEnvironmentDto) =>
    env.type === 'SANDBOX' && !!env.containerId && env.status === 'CONNECTED'

  const handleCreateConnector = () => {
    if (!connectorName.trim()) {
      toast.error('Connector name is required')
      return
    }
    createConnector.mutate(
      { name: connectorName.trim() },
      {
        onSuccess: (data) => {
          setCreatedConnector(data)
          setIsDialogOpen(false)
          setConnectorName('')
        },
      },
    )
  }

  const getStatusBadge = (status: string) => {
    switch (status) {
      case 'CONNECTED':
        return <Badge variant="default">Connected</Badge>
      case 'DISCONNECTED':
        return <Badge variant="secondary">Disconnected</Badge>
      case 'PENDING_RECONNECT':
        return <Badge variant="outline">Pending Reconnect</Badge>
      default:
        return <Badge variant="outline">{status}</Badge>
    }
  }

  return (
    <>
      <Card>
        <CardHeader>
          <div className="flex flex-col space-y-1.5">
            <CardTitle>Execution Environments</CardTitle>
            <CardDescription>View sandbox and connector environments for this team</CardDescription>
          </div>
        </CardHeader>
        <CardContent>
          {environmentFeaturesEnabled && (
            <div className="mb-4 flex justify-end">
              <Button onClick={() => setIsDialogOpen(true)}>
                <Plus className="mr-2 h-4 w-4" />
                Add Workspace Connector
              </Button>
            </div>
          )}
          {isLoading ? (
            <p className="text-muted-foreground text-sm">Loading environments...</p>
          ) : !environments || environments.length === 0 ? (
            <div className="flex h-24 items-center justify-center rounded-md border border-dashed">
              <p className="text-muted-foreground text-sm">No execution environments configured</p>
            </div>
          ) : (
            <>
              <div className="hidden md:block">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Name</TableHead>
                      <TableHead>Type</TableHead>
                      <TableHead>Status</TableHead>
                      <TableHead>Container ID / Image</TableHead>
                      {isOwner && <TableHead className="w-25">Actions</TableHead>}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {environments.map((env) => (
                      <TableRow key={env.id}>
                        <TableCell className="font-medium">{env.name}</TableCell>
                        <TableCell>
                          {env.type === 'CONNECTOR' ? (
                            <Badge variant="outline">CONNECTOR</Badge>
                          ) : (
                            env.type
                          )}
                        </TableCell>
                        <TableCell>{getStatusBadge(env.status)}</TableCell>
                        <TableCell className="text-muted-foreground max-w-50 truncate">
                          {env.containerId || '-'}
                        </TableCell>
                        {isOwner && (
                          <TableCell>
                            <div className="flex items-center gap-2">
                              {isRunningSandbox(env) && (
                                <Button
                                  aria-label={`Terminate ${env.name}`}
                                  onClick={() => handleTerminate(env.id, env.name)}
                                  size="icon"
                                  title="Terminate"
                                  variant="ghost"
                                >
                                  <Square className="h-4 w-4 fill-current" />
                                </Button>
                              )}
                              <Button
                                aria-label={`Delete ${env.name}`}
                                className="text-destructive"
                                onClick={() => handleDelete(env.id, env.name)}
                                size="icon"
                                title="Remove"
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
              <div className="space-y-2 md:hidden" data-testid="environment-cards">
                {environments.map((env) => (
                  <div className="rounded-lg border p-3" key={env.id}>
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <p className="truncate text-sm font-medium">{env.name}</p>
                        <div className="mt-1 flex flex-wrap items-center gap-2">
                          {env.type === 'CONNECTOR' ? (
                            <Badge variant="outline">CONNECTOR</Badge>
                          ) : (
                            <span className="text-muted-foreground text-xs">{env.type}</span>
                          )}
                          {getStatusBadge(env.status)}
                        </div>
                      </div>
                      {isOwner && (
                        <div className="flex shrink-0 gap-1">
                          {isRunningSandbox(env) && (
                            <Button
                              aria-label={`Terminate ${env.name}`}
                              onClick={() => handleTerminate(env.id, env.name)}
                              size="icon"
                              title="Terminate"
                              variant="ghost"
                            >
                              <Square className="h-4 w-4 fill-current" />
                            </Button>
                          )}
                          <Button
                            aria-label={`Delete ${env.name}`}
                            className="text-destructive"
                            onClick={() => handleDelete(env.id, env.name)}
                            size="icon"
                            title="Remove"
                            variant="ghost"
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        </div>
                      )}
                    </div>
                    {env.containerId && (
                      <p className="text-muted-foreground mt-1.5 truncate text-xs">
                        {env.containerId}
                      </p>
                    )}
                  </div>
                ))}
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <Dialog onOpenChange={setIsDialogOpen} open={isDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Add Workspace Connector</DialogTitle>
            <DialogDescription>Enter a name for your new workspace connector.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4 py-4">
            <div
              className="grid grid-cols-1 gap-2 sm:grid-cols-4 sm:items-center sm:gap-4"
              data-testid="connector-name-field"
            >
              <Label className="sm:text-right" htmlFor="name">
                Name
              </Label>
              <Input
                className="sm:col-span-3"
                id="name"
                onChange={(e) => setConnectorName(e.target.value)}
                placeholder="e.g., My Local Workspace"
                value={connectorName}
              />
            </div>
          </div>
          <DialogFooter>
            <Button disabled={createConnector.isPending} onClick={handleCreateConnector}>
              {createConnector.isPending ? 'Creating...' : 'Create Connector'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog onOpenChange={(open) => !open && setCreatedConnector(null)} open={!!createdConnector}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>Workspace Connector Created</DialogTitle>
            <DialogDescription>
              Your connector has been created. Use the following command to install and connect it.
            </DialogDescription>
          </DialogHeader>
          {createdConnector && (
            <div className="grid gap-4 py-4">
              <div className="grid gap-2">
                <Label>Install Command</Label>
                <div className="flex items-center gap-2">
                  <code className="bg-muted min-w-0 flex-1 rounded-md p-2 text-sm break-all">
                    {createdConnector.installCommand}
                  </code>
                  <Button
                    aria-label="Copy install command"
                    className="shrink-0"
                    onClick={() => void copyToClipboard(createdConnector.installCommand)}
                    size="icon"
                    variant="outline"
                  >
                    <Copy className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            </div>
          )}
          <DialogFooter>
            <Button onClick={() => setCreatedConnector(null)}>Close</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

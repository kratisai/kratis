import { Key, Loader2, Trash2, TriangleAlert } from 'lucide-react'
import { useState } from 'react'

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
import {
  useAffectedRepositories,
  useCredentials,
  useDeleteCredential,
} from '@/hooks/use-credentials'

export function CredentialsPanel() {
  const { data: credentials, isLoading } = useCredentials()
  const deleteCredential = useDeleteCredential()
  const [credentialToDelete, setCredentialToDelete] = useState<null | string>(null)

  const { data: affectedRepos } = useAffectedRepositories(credentialToDelete ?? undefined)

  const handleDelete = () => {
    if (credentialToDelete) {
      deleteCredential.mutate(credentialToDelete)
      setCredentialToDelete(null)
    }
  }

  const credentialNameToDelete = credentials?.find((c) => c.id === credentialToDelete)?.name

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-2">
          <Key className="h-5 w-5" />
          <CardTitle>Credentials</CardTitle>
        </div>
        <CardDescription>
          Credentials are added when adding repositories to Kratis. They are used to authenticate
          with your version control providers.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <div className="flex justify-center py-6">
            <Loader2 className="text-muted-foreground h-6 w-6 animate-spin" />
          </div>
        ) : !credentials || credentials.length === 0 ? (
          <div className="rounded-lg border border-dashed p-6 text-center">
            <Key className="text-muted-foreground mx-auto mb-2 h-8 w-8 opacity-50" />
            <p className="text-sm font-medium">No credentials configured</p>
            <p className="text-muted-foreground mt-1 text-xs">
              Credentials will appear here when you add repositories to Kratis
            </p>
          </div>
        ) : (
          <div className="space-y-2">
            {credentials.map((cred) => (
              <div
                className="flex items-center justify-between rounded-lg border p-3"
                key={cred.id}
              >
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{cred.name}</span>
                    <Badge
                      className="scale-95 py-0 text-[9px] tracking-wider uppercase"
                      variant="outline"
                    >
                      {cred.type.replace('_', ' ')}
                    </Badge>
                  </div>
                  {cred.publicKey && (
                    <div className="text-muted-foreground mt-0.5 truncate text-xs">
                      Key: {cred.publicKey.substring(0, 40)}...
                    </div>
                  )}
                </div>
                <Button
                  aria-label={`Delete ${cred.name}`}
                  className="h-8 w-8 p-0"
                  disabled={deleteCredential.isPending}
                  onClick={() => setCredentialToDelete(cred.id)}
                  size="sm"
                  type="button"
                  variant="ghost"
                >
                  <Trash2 className="text-destructive h-4 w-4" />
                </Button>
              </div>
            ))}
          </div>
        )}
      </CardContent>

      <Dialog
        onOpenChange={(open) => !open && setCredentialToDelete(null)}
        open={!!credentialToDelete}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <TriangleAlert className="text-destructive h-5 w-5" />
              Delete Credential
            </DialogTitle>
            <DialogDescription>
              Are you sure you want to delete the credential "{credentialNameToDelete}"? This action
              cannot be undone.
            </DialogDescription>
          </DialogHeader>

          {affectedRepos && affectedRepos.length > 0 && (
            <div className="border-destructive/30 bg-destructive/5 rounded-lg border p-4">
              <p className="text-destructive mb-2 text-sm font-medium">
                This credential is currently in use. Removing it will deregister{' '}
                {affectedRepos.length} {affectedRepos.length === 1 ? 'repository' : 'repositories'}{' '}
                that use this credential:
              </p>
              <ul className="text-muted-foreground max-h-32 list-inside list-disc overflow-y-auto text-sm">
                {affectedRepos.map((repo) => (
                  <li key={repo.id}>{repo.name}</li>
                ))}
              </ul>
            </div>
          )}

          <DialogFooter>
            <Button onClick={() => setCredentialToDelete(null)} type="button" variant="outline">
              Cancel
            </Button>
            <Button
              disabled={deleteCredential.isPending}
              onClick={handleDelete}
              type="button"
              variant="destructive"
            >
              {deleteCredential.isPending ? 'Deleting...' : 'Delete'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Card>
  )
}

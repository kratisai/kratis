import { Check, Key, Loader2, Plus } from 'lucide-react'

import type { RepoCredentialDto } from '@/types/auth-types'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { ScrollArea } from '@/components/ui/scroll-area'

interface CredentialListProps {
  credentials: RepoCredentialDto[]
  isLoading: boolean
  onAddNew: () => void
  onSelect: (credId: string) => void
  selectedCredentialId: string
}

export function CredentialList({
  credentials,
  isLoading,
  onAddNew,
  onSelect,
  selectedCredentialId,
}: CredentialListProps) {
  return (
    <div className="flex min-h-0 flex-1 flex-col space-y-3">
      <div className="flex shrink-0 items-center justify-between">
        <Label className="text-sm font-medium">Select Saved Authentication</Label>
        <Button
          className="h-8 text-xs"
          onClick={onAddNew}
          size="sm"
          type="button"
          variant="outline"
        >
          <Plus className="mr-1 h-3.5 w-3.5" /> New Credential
        </Button>
      </div>

      {isLoading ? (
        <div className="flex flex-1 items-center justify-center py-6">
          <Loader2 className="text-muted-foreground h-6 w-6 animate-spin" />
        </div>
      ) : credentials.length === 0 ? (
        <div className="flex flex-1 flex-col justify-center rounded-lg border border-dashed p-6 text-center">
          <Key className="text-muted-foreground mx-auto mb-2 h-8 w-8 opacity-50" />
          <p className="text-sm font-medium">No saved credentials</p>
          <p className="text-muted-foreground mt-1 text-xs">
            Configure credentials to discover and pull private repositories
          </p>
        </div>
      ) : (
        <ScrollArea className="bg-accent/20 min-h-[200px] flex-1 rounded-lg border p-2">
          <div className="space-y-1.5">
            {credentials.map((cred) => (
              <button
                className={`flex w-full min-w-0 items-center justify-between gap-3 rounded-lg border p-3 text-left text-sm transition-colors ${
                  selectedCredentialId === cred.id
                    ? 'border-primary bg-primary/5 text-primary'
                    : 'border-border bg-card hover:bg-accent/40'
                }`}
                key={cred.id}
                onClick={() => onSelect(cred.id)}
                type="button"
              >
                <div className="min-w-0 flex-1">
                  <div className="flex min-w-0 items-center gap-1.5 font-semibold">
                    <span
                      className="block min-w-0 flex-1 truncate text-xs sm:text-sm"
                      title={cred.name}
                    >
                      {cred.name}
                    </span>
                    <Badge
                      className="shrink-0 scale-95 py-0 text-[9px] tracking-wider uppercase"
                      variant="outline"
                    >
                      {cred.type.replace('_', ' ')}
                    </Badge>
                  </div>
                  {cred.publicKey && (
                    <div
                      className="text-muted-foreground mt-0.5 truncate text-[10px]"
                      title={cred.publicKey}
                    >
                      Key: {cred.publicKey.substring(0, 30)}...
                    </div>
                  )}
                </div>
                {selectedCredentialId === cred.id && (
                  <Check className="text-primary h-4 w-4 shrink-0" />
                )}
              </button>
            ))}
          </div>
        </ScrollArea>
      )}
    </div>
  )
}

import type { FieldErrors, UseFormRegister } from 'react-hook-form'

import { ArrowLeft, CheckCircle2, Loader2 } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { DialogFooter } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

import type { RepositoryFormData } from './repository-form-types'

import { IngestImmediatelyCheckbox } from './ingest-immediately-checkbox'

interface RepositoryDetailsFormProps {
  credentialName: null | string
  errors: FieldErrors<RepositoryFormData>
  ingestImmediately: boolean
  isEditing: boolean
  isPending: boolean
  onBack: () => void
  onCancel: () => void
  onIngestImmediatelyChange: (checked: boolean) => void
  register: UseFormRegister<RepositoryFormData>
}

export function RepositoryDetailsForm({
  credentialName,
  errors,
  ingestImmediately,
  isEditing,
  isPending,
  onBack,
  onCancel,
  onIngestImmediatelyChange,
  register,
}: RepositoryDetailsFormProps) {
  return (
    <div className="flex h-full min-h-0 flex-1 flex-col py-2">
      <div className="min-h-0 flex-1 space-y-4 overflow-y-auto pr-1 pb-4">
        {credentialName && (
          <div className="bg-secondary/35 text-muted-foreground flex items-center gap-2 rounded-lg border p-3 text-xs">
            <CheckCircle2 className="h-4 w-4 shrink-0 text-green-500" />
            <div className="truncate">
              Authenticated via: <span className="text-foreground font-bold">{credentialName}</span>
            </div>
          </div>
        )}

        <div className="grid gap-3">
          <div className="grid gap-1.5">
            <Label htmlFor="url">Repository Git URL</Label>
            <Input
              id="url"
              {...register('url')}
              disabled={isEditing}
              placeholder="https://github.com/myorg/my-project.git"
            />
            {errors.url && <p className="text-destructive text-xs">{errors.url.message}</p>}
          </div>

          <div className="grid gap-1.5">
            <Label htmlFor="name">Display Name</Label>
            <Input id="name" {...register('name')} placeholder="my-project" />
            {errors.name && <p className="text-destructive text-xs">{errors.name.message}</p>}
          </div>

          <div className="grid gap-1.5">
            <Label htmlFor="branch">Default Branch</Label>
            <Input id="branch" {...register('branch')} placeholder="main" />
            {errors.branch && <p className="text-destructive text-xs">{errors.branch.message}</p>}
          </div>
        </div>
      </div>

      {!isEditing && (
        <div className="shrink-0 pb-4">
          <IngestImmediatelyCheckbox
            checked={ingestImmediately}
            onChange={onIngestImmediatelyChange}
          />
        </div>
      )}

      <DialogFooter className="mt-auto flex shrink-0 flex-col-reverse gap-2 border-t pt-4 sm:flex-row sm:justify-end">
        <Button onClick={isEditing ? onCancel : onBack} type="button" variant="outline">
          {isEditing ? (
            'Cancel'
          ) : (
            <>
              <ArrowLeft className="mr-2 h-4 w-4" /> Back
            </>
          )}
        </Button>
        <Button disabled={isPending} type="submit">
          {isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
          {isEditing ? 'Update' : 'Onboard'} Repository
        </Button>
      </DialogFooter>
    </div>
  )
}

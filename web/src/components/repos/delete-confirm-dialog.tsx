import { Loader2 } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

export function DeleteConfirmDialog({
  isPending,
  onCancel,
  onConfirm,
  repoName,
}: {
  isPending: boolean
  onCancel: () => void
  onConfirm: () => void
  repoName: string
}) {
  return (
    <Dialog onOpenChange={(open) => !open && onCancel()} open={!!repoName}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Delete Repository</DialogTitle>
          <DialogDescription>
            Are you sure you want to delete "{repoName}"? This action cannot be undone.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button disabled={isPending} onClick={onCancel} type="button" variant="outline">
            Cancel
          </Button>
          <Button disabled={isPending} onClick={onConfirm} variant="destructive">
            {isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
            Delete
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

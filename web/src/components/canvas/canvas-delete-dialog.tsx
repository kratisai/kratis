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

export function CanvasDeleteDialog({
  documentTitle,
  isPending,
  onCancel,
  onConfirm,
  open,
}: {
  documentTitle: string
  isPending: boolean
  onCancel: () => void
  onConfirm: () => void
  open: boolean
}) {
  return (
    <Dialog onOpenChange={(openState) => !openState && onCancel()} open={open}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Delete Document</DialogTitle>
          <DialogDescription>
            Are you sure you want to delete "{documentTitle}"? This action cannot be undone.
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

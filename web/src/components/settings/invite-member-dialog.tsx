import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'

import { Button } from '@/components/ui/button'
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

const inviteSchema = z.object({
  email: z.string().email('Please enter a valid email address'),
  role: z.enum(['member', 'owner']),
})

type InviteFormData = z.infer<typeof inviteSchema>

interface InviteMemberDialogProps {
  onOpenChange: (open: boolean) => void
  onSubmit: (data: InviteFormData) => void
  open: boolean
}

export function InviteMemberDialog({ onOpenChange, onSubmit, open }: InviteMemberDialogProps) {
  const [isSubmitting, setIsSubmitting] = useState(false)

  const {
    formState: { errors },
    handleSubmit,
    register,
    reset,
  } = useForm<InviteFormData>({
    defaultValues: {
      email: '',
      role: 'member',
    },
    resolver: zodResolver(inviteSchema),
  })

  const handleFormSubmit = (data: InviteFormData) => {
    setIsSubmitting(true)
    try {
      onSubmit(data)
      reset()
      onOpenChange(false)
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <Dialog onOpenChange={onOpenChange} open={open}>
      <DialogContent className="sm:max-w-[425px]">
        <DialogHeader>
          <DialogTitle>Invite Team Member</DialogTitle>
          <DialogDescription>
            Add a new member to your team by entering their email address.
          </DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(e) => {
            void handleSubmit(handleFormSubmit)(e)
          }}
        >
          <div className="grid gap-4 py-4">
            <div className="grid gap-2">
              <Label htmlFor="email">Email</Label>
              <Input
                id="email"
                placeholder="colleague@example.com"
                type="email"
                {...register('email')}
              />
              {errors.email && <p className="text-destructive text-sm">{errors.email.message}</p>}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="role">Role</Label>
              <select
                className="border-input bg-background ring-offset-background focus-visible:ring-ring flex h-10 w-full rounded-md border px-3 py-2 text-sm focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none"
                id="role"
                {...register('role')}
              >
                <option value="member">Member</option>
                <option value="owner">Owner</option>
              </select>
            </div>
          </div>
          <DialogFooter>
            <Button onClick={() => onOpenChange(false)} type="button" variant="outline">
              Cancel
            </Button>
            <Button disabled={isSubmitting} type="submit">
              {isSubmitting ? 'Inviting...' : 'Invite Member'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

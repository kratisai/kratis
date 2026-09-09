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

const createTeamSchema = z.object({
  description: z.string().max(1000).optional(),
  name: z.string().min(1, 'Team name is required').max(255),
})

interface CreateTeamDialogProps {
  onOpenChange: (open: boolean) => void
  onSubmit: (data: CreateTeamFormData) => void
  open: boolean
}

type CreateTeamFormData = z.infer<typeof createTeamSchema>

export function CreateTeamDialog({ onOpenChange, onSubmit, open }: CreateTeamDialogProps) {
  const [isSubmitting, setIsSubmitting] = useState(false)

  const {
    formState: { errors },
    handleSubmit,
    register,
    reset,
  } = useForm<CreateTeamFormData>({
    defaultValues: {
      description: '',
      name: '',
    },
    resolver: zodResolver(createTeamSchema),
  })

  const handleFormSubmit = (data: CreateTeamFormData) => {
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
          <DialogTitle>Create Team</DialogTitle>
          <DialogDescription>Create a new team to collaborate with others.</DialogDescription>
        </DialogHeader>
        <form
          onSubmit={(e) => {
            void handleSubmit(handleFormSubmit)(e)
          }}
        >
          <div className="grid gap-4 py-4">
            <div className="grid gap-2">
              <Label htmlFor="team-name">Team Name</Label>
              <Input id="team-name" placeholder="My Team" {...register('name')} />
              {errors.name && <p className="text-destructive text-sm">{errors.name.message}</p>}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="team-description">Description (optional)</Label>
              <Input
                id="team-description"
                placeholder="A team for my project"
                {...register('description')}
              />
              {errors.description && (
                <p className="text-destructive text-sm">{errors.description.message}</p>
              )}
            </div>
          </div>
          <DialogFooter>
            <Button onClick={() => onOpenChange(false)} type="button" variant="outline">
              Cancel
            </Button>
            <Button disabled={isSubmitting} type="submit">
              {isSubmitting ? 'Creating...' : 'Create Team'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

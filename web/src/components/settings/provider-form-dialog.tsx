import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import * as z from 'zod'

import type { EnvironmentProviderDto } from '@/lib/provider-api'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form'
import { Input } from '@/components/ui/input'

const formSchema = z.object({
  dockerImage: z
    .string()
    .max(255, 'Docker image must be at most 255 characters')
    .optional()
    .or(z.literal('')),
  name: z.string().min(1, 'Name is required').max(100, 'Name must be at most 100 characters'),
})

type FormValues = z.infer<typeof formSchema>

export type { FormValues }

interface ProviderFormDialogProps {
  onOpenChange: (open: boolean) => void
  onSubmit: (data: FormValues) => void
  open: boolean
  provider?: EnvironmentProviderDto
}

export function ProviderFormDialog({
  onOpenChange,
  onSubmit,
  open,
  provider,
}: ProviderFormDialogProps) {
  const form = useForm<FormValues>({
    defaultValues: {
      dockerImage: '',
      name: '',
    },
    resolver: zodResolver(formSchema),
  })

  useEffect(() => {
    if (open) {
      if (provider) {
        form.reset({
          dockerImage: provider.dockerImage ?? '',
          name: provider.name,
        })
      } else {
        form.reset({
          dockerImage: '',
          name: '',
        })
      }
    }
  }, [open, provider, form])

  const handleSubmit = (values: FormValues) => {
    const submitData = {
      ...values,
      dockerImage: values.dockerImage === '' ? undefined : values.dockerImage,
    }
    onSubmit(submitData)
    onOpenChange(false)
  }

  return (
    <Dialog onOpenChange={onOpenChange} open={open}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {provider ? 'Edit Environment Provider' : 'Add Environment Provider'}
          </DialogTitle>
          <DialogDescription>
            Configure your environment provider settings (e.g., Docker image).
          </DialogDescription>
        </DialogHeader>
        <Form {...form}>
          <form
            autoComplete="off"
            className="space-y-4"
            onSubmit={(e) => {
              void form.handleSubmit(handleSubmit)(e)
            }}
          >
            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Name</FormLabel>
                  <FormControl>
                    <Input autoComplete="off" placeholder="e.g., Java Docker Provider" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="dockerImage"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Docker Image (Optional)</FormLabel>
                  <FormControl>
                    <Input
                      autoComplete="off"
                      placeholder="e.g., java:17 or kratis-runner-base:latest"
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <DialogFooter>
              <Button type="submit">{provider ? 'Update' : 'Add'} Provider</Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}

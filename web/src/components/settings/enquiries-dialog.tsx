import { ExternalLink, Loader2, MessageCircle } from 'lucide-react'
import { useState } from 'react'

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useInstallationInfo } from '@/hooks/use-config'
import { useAuthStore } from '@/store/auth-store'

interface EnquiriesDialogProps {
  formId?: string
  onOpenChange: (open: boolean) => void
  open: boolean
}

const DEFAULT_TALLY_FORM_ID = 'Npjy0Q'

export function EnquiriesDialog({
  formId = DEFAULT_TALLY_FORM_ID,
  onOpenChange,
  open,
}: EnquiriesDialogProps) {
  const { user } = useAuthStore()
  const { data: installationInfo } = useInstallationInfo(open)
  const [isLoading, setIsLoading] = useState(true)

  const params = new URLSearchParams({
    alignLeft: '1',
    dynamicHeight: '1',
    hideTitle: '1',
    transparentBackground: '1',
  })

  if (user?.email) {
    params.set('email', user.email)
  }
  if (user?.name) {
    params.set('name', user.name)
  }
  if (installationInfo?.installId) {
    params.set('install_id', installationInfo.installId)
  }

  const embedUrl = `https://tally.so/embed/${formId}?${params.toString()}`
  const externalUrl = `https://tally.so/r/${formId}`

  return (
    <Dialog onOpenChange={onOpenChange} open={open}>
      <DialogContent className="flex max-h-[90vh] w-full flex-col gap-0 p-0 sm:max-w-2xl">
        <DialogHeader className="border-b px-6 py-4">
          <div className="flex items-center justify-between pr-6">
            <div className="flex items-center gap-2">
              <MessageCircle className="text-primary h-5 w-5" />
              <DialogTitle>Send an enquiry</DialogTitle>
            </div>
            <a
              className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1 text-xs transition-colors"
              href={externalUrl}
              rel="noopener noreferrer"
              target="_blank"
            >
              Open in new tab <ExternalLink className="h-3 w-3" />
            </a>
          </div>
          <DialogDescription className="mt-1.5 text-left text-sm">
            Tell us your use case. We can also discuss paid options for support or features.
          </DialogDescription>
        </DialogHeader>

        <div className="bg-background relative min-h-[500px] w-full flex-1 overflow-y-auto">
          {isLoading && (
            <div
              className="text-muted-foreground absolute inset-0 flex flex-col items-center justify-center gap-2"
              data-testid="tally-loading"
            >
              <Loader2 className="h-6 w-6 animate-spin" />
              <p className="text-xs">Loading form...</p>
            </div>
          )}
          <iframe
            className="h-[520px] w-full border-0"
            data-testid="tally-iframe"
            loading="lazy"
            onLoad={() => setIsLoading(false)}
            src={embedUrl}
            title="Send an enquiry"
          />
        </div>
      </DialogContent>
    </Dialog>
  )
}

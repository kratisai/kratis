import type { ReactNode } from 'react'

import { Check, Loader2 } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { cn } from '@/lib/utils'

export interface WizardStep {
  id: string
  label: string
}

interface WizardFooterProps {
  backDisabled?: boolean
  backLabel?: string
  nextDisabled?: boolean
  nextLabel?: string
  nextPending?: boolean
  nextPendingLabel?: string
  onBack?: () => void
  onNext?: () => void
  onSubmit?: () => void
  submitDisabled?: boolean
  submitLabel?: string
  submitPending?: boolean
  submitPendingLabel?: string
}

interface WizardShellProps {
  children: ReactNode
  currentStepId: string
  description: string
  footer?: ReactNode
  onOpenChange: (open: boolean) => void
  open: boolean
  steps: WizardStep[]
  title: string
}

export function WizardFooter({
  backDisabled,
  backLabel,
  nextDisabled,
  nextLabel,
  nextPending,
  nextPendingLabel,
  onBack,
  onNext,
  onSubmit,
  submitDisabled,
  submitLabel,
  submitPending,
  submitPendingLabel,
}: WizardFooterProps) {
  return (
    <div className="flex flex-col-reverse justify-end gap-2 sm:flex-row">
      {onBack && (
        <Button disabled={backDisabled} onClick={onBack} type="button" variant="outline">
          {backLabel ?? 'Back'}
        </Button>
      )}
      {onNext && (
        <Button disabled={nextDisabled || nextPending} onClick={onNext} type="button">
          {nextPending ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              {nextPendingLabel ?? 'Working…'}
            </>
          ) : (
            <>{nextLabel ?? 'Next'}</>
          )}
        </Button>
      )}
      {onSubmit && (
        <Button disabled={submitDisabled || submitPending} onClick={onSubmit} type="button">
          {submitPending ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              {submitPendingLabel ?? 'Saving…'}
            </>
          ) : (
            <>{submitLabel ?? 'Save'}</>
          )}
        </Button>
      )}
    </div>
  )
}

export function WizardShell({
  children,
  currentStepId,
  description,
  footer,
  onOpenChange,
  open,
  steps,
  title,
}: WizardShellProps) {
  const currentIndex = Math.max(
    steps.findIndex((step) => step.id === currentStepId),
    0,
  )
  return (
    <Dialog onOpenChange={onOpenChange} open={open}>
      <DialogContent className="fixed inset-0 top-0 left-0 z-50 flex h-screen w-screen max-w-none translate-x-0 translate-y-0 flex-col overflow-hidden rounded-none border-none p-4 sm:top-[50%] sm:left-[50%] sm:h-[min(85vh,40rem)] sm:w-full sm:max-w-[720px] sm:translate-x-[-50%] sm:translate-y-[-50%] sm:rounded-lg sm:border sm:p-6 sm:shadow-lg md:max-w-[800px]">
        <DialogHeader className="shrink-0 border-b pb-2">
          <DialogTitle className="text-lg font-semibold">{title}</DialogTitle>
          <DialogDescription className="text-xs">{description}</DialogDescription>
          {steps.length > 1 && <StepIndicator currentIndex={currentIndex} steps={steps} />}
        </DialogHeader>
        <div className="flex min-h-0 flex-1 flex-col">{children}</div>
        {footer && <div className="shrink-0 border-t pt-4">{footer}</div>}
      </DialogContent>
    </Dialog>
  )
}

function StepIndicator({ currentIndex, steps }: { currentIndex: number; steps: WizardStep[] }) {
  return (
    <ol aria-label="Wizard steps" className="flex flex-wrap items-center gap-1.5 pt-1">
      {steps.map((step, index) => {
        const isComplete = index < currentIndex
        const isCurrent = index === currentIndex
        return (
          <li className="flex items-center gap-1.5" key={step.id}>
            {index > 0 && (
              <span
                aria-hidden="true"
                className={cn('h-px w-4', isComplete || isCurrent ? 'bg-primary' : 'bg-border')}
              />
            )}
            <span
              aria-current={isCurrent ? 'step' : undefined}
              className={cn(
                'flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium',
                isCurrent
                  ? 'bg-primary/10 text-primary'
                  : isComplete
                    ? 'text-primary'
                    : 'text-muted-foreground',
              )}
            >
              <span
                className={cn(
                  'flex h-4 w-4 items-center justify-center rounded-full text-[10px]',
                  isComplete
                    ? 'bg-primary text-primary-foreground'
                    : isCurrent
                      ? 'bg-primary/15 text-primary'
                      : 'bg-muted text-muted-foreground',
                )}
              >
                {isComplete ? <Check className="h-2.5 w-2.5" /> : index + 1}
              </span>
              <span className="hidden sm:inline">{step.label}</span>
            </span>
          </li>
        )
      })}
    </ol>
  )
}

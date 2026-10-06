import { Sparkles } from 'lucide-react'

import { Button } from '@/components/ui/button'

export const PLAN_REQUEST_MESSAGE = 'Please create an implementation plan'

interface PlanShortcutProps {
  disabled?: boolean
  onGenerate: () => void
}

export function PlanShortcut({ disabled = false, onGenerate }: PlanShortcutProps) {
  return (
    <div className="mb-2 flex justify-end" data-testid="plan-shortcut">
      <Button
        className="text-muted-foreground hover:text-primary"
        disabled={disabled}
        onClick={onGenerate}
        size="sm"
        type="button"
        variant="outline"
      >
        <Sparkles className="h-3.5 w-3.5" />
        Ready to go? Let's generate an implementation plan.
      </Button>
    </div>
  )
}

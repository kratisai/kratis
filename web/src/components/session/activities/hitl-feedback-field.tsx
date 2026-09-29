import { ChevronDown } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { cn } from '@/lib/utils'

interface HitlFeedbackFieldProps {
  disabled?: boolean
  id: string
  onChange: (value: string) => void
  placeholder: string
  value: string
}

export function HitlFeedbackField({
  disabled,
  id,
  onChange,
  placeholder,
  value,
}: HitlFeedbackFieldProps) {
  const [open, setOpen] = useState(false)
  const panelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (open) {
      panelRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
    }
  }, [open])

  return (
    <div className="space-y-2" ref={panelRef}>
      <Button
        aria-expanded={open}
        className="w-full justify-between"
        onClick={() => setOpen((prev) => !prev)}
        size="sm"
        type="button"
        variant="ghost"
      >
        <span>Message to agent</span>
        <ChevronDown className={cn('h-4 w-4 transition-transform', open && 'rotate-180')} />
      </Button>
      {open && (
        <Textarea
          aria-label="Message to agent"
          className="min-h-16 text-xs"
          disabled={disabled}
          id={id}
          maxLength={4000}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder}
          value={value}
        />
      )}
    </div>
  )
}

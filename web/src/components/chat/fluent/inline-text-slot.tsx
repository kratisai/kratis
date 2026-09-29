import { type ChangeEvent, useEffect, useRef } from 'react'

import { cn } from '@/lib/utils'

interface InlineTextSlotProps {
  disabled?: boolean
  onChange: (value: string) => void
  onKeyDown?: (e: React.KeyboardEvent<HTMLInputElement>) => void
  placeholder?: string
  value?: string
}

export function InlineTextSlot({
  disabled = false,
  onChange,
  onKeyDown,
  placeholder = 'enter text...',
  value = '',
}: InlineTextSlotProps) {
  const spanRef = useRef<HTMLSpanElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const handleChange = (e: ChangeEvent<HTMLInputElement>) => {
    onChange(e.target.value)
  }

  // Measure text width dynamically using an invisible span
  useEffect(() => {
    if (!spanRef.current || !inputRef.current) return
    const measuredWidth = spanRef.current.offsetWidth
    inputRef.current.style.width = `${Math.max(measuredWidth + 12, 100)}px`
  }, [value, placeholder])

  return (
    <span className="relative inline-flex max-w-full items-baseline">
      {/* Hidden text measurer mirror */}
      <span
        aria-hidden="true"
        className="pointer-events-none invisible absolute px-1 text-sm font-medium whitespace-pre"
        ref={spanRef}
      >
        {value || placeholder}
      </span>
      <input
        className={cn(
          'border-primary/50 bg-primary/5 text-foreground h-7 max-w-full min-w-[100px] rounded border-b-2 px-2 py-0 text-sm font-medium transition-all',
          'placeholder:text-muted-foreground/70 placeholder:font-normal placeholder:italic',
          'focus:border-primary focus:bg-primary/10 focus:ring-0 focus:outline-none',
          disabled && 'cursor-not-allowed opacity-50',
        )}
        disabled={disabled}
        onChange={handleChange}
        onKeyDown={onKeyDown}
        placeholder={placeholder}
        ref={inputRef}
        type="text"
        value={value}
      />
    </span>
  )
}

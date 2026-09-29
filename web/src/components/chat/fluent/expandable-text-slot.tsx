import { type ChangeEvent, useCallback, useEffect, useRef } from 'react'

import { cn } from '@/lib/utils'

interface ExpandableTextSlotProps {
  disabled?: boolean
  onChange: (value: string) => void
  onKeyDown?: (e: React.KeyboardEvent<HTMLTextAreaElement>) => void
  placeholder?: string
  value?: string
}

export function ExpandableTextSlot({
  disabled = false,
  onChange,
  onKeyDown,
  placeholder = 'paste error logs or stack trace...',
  value = '',
}: ExpandableTextSlotProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const resize = useCallback(() => {
    const textarea = textareaRef.current
    if (!textarea) return
    textarea.style.height = 'auto'
    const nextHeight = Math.max(textarea.scrollHeight, 32)
    textarea.style.height = `${Math.min(nextHeight, 240)}px`
  }, [])

  useEffect(() => {
    resize()
  }, [value, resize])

  const handleChange = (e: ChangeEvent<HTMLTextAreaElement>) => {
    onChange(e.target.value)
  }

  const isMultiLine = value.includes('\n') || value.length > 50

  return (
    <div
      className={cn(
        'relative transition-all',
        isMultiLine ? 'my-1.5 block w-full' : 'inline-block align-baseline',
      )}
    >
      <textarea
        className={cn(
          'border-primary/30 bg-muted/40 text-foreground rounded border font-mono text-xs transition-all',
          'placeholder:text-muted-foreground/70 placeholder:font-sans placeholder:italic',
          'focus:border-primary focus:bg-background focus:ring-ring focus:ring-1 focus:outline-none',
          isMultiLine
            ? 'w-full resize-y p-2.5'
            : 'h-7 max-w-[400px] min-w-[220px] resize-none px-2 py-1 align-baseline',
          disabled && 'cursor-not-allowed opacity-50',
        )}
        disabled={disabled}
        onChange={handleChange}
        onKeyDown={onKeyDown}
        placeholder={placeholder}
        ref={textareaRef}
        rows={isMultiLine ? 3 : 1}
        value={value}
      />
    </div>
  )
}

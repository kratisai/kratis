import { Send } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'

import { ModelSelectorDropdown } from '@/components/chat/model-selector-dropdown'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { useUIStore } from '@/store/ui-store'

interface UserMessageInputProps {
  disabled?: boolean
  initialValue?: string
  onSend: (message: string) => void
  placeholder?: string
}

// Line height calculation: ~20px per line (font + padding)
// 2.5 lines ≈ 50px, 15 lines ≈ 300px
const MIN_ROWS = 3
const MAX_ROWS = 15

export function UserMessageInput({
  disabled = false,
  initialValue = '',
  onSend,
  placeholder = 'Type a message...',
}: UserMessageInputProps) {
  const [input, setInput] = useState(initialValue)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const { selectedModelName, selectedProviderId } = useUIStore()

  const isModelSelected = selectedModelName !== null && selectedProviderId !== null

  // Auto-resize textarea based on content
  const resizeTextarea = useCallback(() => {
    const textarea = textareaRef.current
    if (!textarea) return

    // Reset height to auto to get the correct scrollHeight
    textarea.style.height = 'auto'

    // Calculate max height based on 15 lines
    const lineHeight = 20 // approximate line height in pixels
    const maxHeight = MAX_ROWS * lineHeight

    // Set new height, capped at max
    const newHeight = Math.min(textarea.scrollHeight, maxHeight)
    textarea.style.height = `${newHeight}px`
  }, [])

  useEffect(() => {
    resizeTextarea()
  }, [input, resizeTextarea])

  // Handle window resize
  useEffect(() => {
    const handleResize = () => resizeTextarea()
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [resizeTextarea])

  const handleSend = () => {
    if (!input.trim() || disabled || !isModelSelected) return
    onSend(input.trim())
    setInput('')
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey) {
      e.preventDefault()
      handleSend()
    }
    // Ctrl+Enter or Shift+Enter inserts newline (default textarea behavior)
  }

  return (
    <div className="flex w-full items-end gap-2">
      {/* Wrapper with border containing textarea and model selector */}
      <div className="border-input relative flex min-w-0 flex-1 flex-col rounded-lg border-2 bg-transparent p-2">
        {/* Textarea - no border, fills remaining space */}
        <textarea
          className={cn(
            'placeholder:text-foreground/80 w-full resize-none border-none bg-transparent px-3 py-2 pb-2 text-sm focus:outline-none disabled:cursor-not-allowed disabled:opacity-50',
          )}
          disabled={disabled}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={placeholder}
          ref={textareaRef}
          rows={MIN_ROWS}
          value={input}
        />

        {/* Model Selector - positioned at bottom-left, sitting on the border */}
        <div className="absolute right-0 bottom-0 translate-y-0">
          <ModelSelectorDropdown />
        </div>
      </div>

      {/* Send Button - outside wrapper */}
      <Button
        aria-label="Send"
        className="mb-5 h-10 w-10 flex-shrink-0 self-end"
        disabled={!input.trim() || disabled || !isModelSelected}
        onClick={handleSend}
        size="icon"
        type="button"
      >
        <Send className="h-4 w-4" />
      </Button>
    </div>
  )
}

import { ChevronDown, ChevronUp, Eye, Send, Sparkles } from 'lucide-react'
import { useState } from 'react'

import type { AskTemplate } from '@/types/ask-template'

import { ModelSelectorDropdown } from '@/components/chat/model-selector-dropdown'
import { Button } from '@/components/ui/button'
import { useUIStore } from '@/store/ui-store'

import { ExpandableTextSlot } from './expandable-text-slot'
import { InlineTextSlot } from './inline-text-slot'
import { RepoSelectSlot } from './repo-select-slot'

interface FluentSentenceInputProps {
  disabled?: boolean
  onSend: (compiledPrompt: string) => void
  template: AskTemplate
}

export function FluentSentenceInput({
  disabled = false,
  onSend,
  template,
}: FluentSentenceInputProps) {
  const [slotValues, setSlotValues] = useState<Record<string, string>>({})
  const [showPreview, setShowPreview] = useState(false)
  const { selectedModelName, selectedProviderId } = useUIStore()

  const isModelSelected = selectedModelName !== null && selectedProviderId !== null

  const handleSlotChange = (key: string, value: string) => {
    setSlotValues((prev) => ({
      ...prev,
      [key]: value,
    }))
  }

  // Check if required slots are filled
  const isComplete = template.segments.every((segment) => {
    if (segment.type !== 'slot') return true
    if (segment.optional) return true
    const val = slotValues[segment.key]
    return typeof val === 'string' && val.trim().length > 0
  })

  const compiledPrompt = template.compilePrompt(slotValues)

  const handleSend = () => {
    if (!isComplete || disabled || !isModelSelected) return
    onSend(compiledPrompt)
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey) {
      e.preventDefault()
      handleSend()
    }
  }

  return (
    <div className="flex w-full flex-col gap-2">
      {/* Fluent sentence box */}
      <div className="border-primary/20 bg-card/60 focus-within:border-primary/50 relative flex flex-col rounded-xl border-2 p-4 shadow-sm backdrop-blur transition-all focus-within:shadow-md">
        {/* Sentence prose flow */}
        <div
          className="text-foreground inline-flex flex-wrap items-baseline gap-2 pb-8 text-base leading-relaxed"
          onKeyDown={handleKeyDown}
        >
          {template.segments.map((segment, index) => {
            if (segment.type === 'text') {
              return (
                <span
                  className="text-muted-foreground font-normal select-none"
                  key={`text-${index}`}
                >
                  {segment.value}
                </span>
              )
            }

            const slot = segment
            const currentValue = slotValues[slot.key] || ''

            if (slot.control === 'repo-select') {
              return (
                <RepoSelectSlot
                  disabled={disabled}
                  key={slot.key}
                  onChange={(val) => handleSlotChange(slot.key, val)}
                  placeholder={slot.placeholder}
                  value={currentValue}
                />
              )
            }

            if (slot.control === 'inline-text') {
              return (
                <InlineTextSlot
                  disabled={disabled}
                  key={slot.key}
                  onChange={(val) => handleSlotChange(slot.key, val)}
                  onKeyDown={handleKeyDown}
                  placeholder={slot.placeholder}
                  value={currentValue}
                />
              )
            }

            return (
              <ExpandableTextSlot
                disabled={disabled}
                key={slot.key}
                onChange={(val) => handleSlotChange(slot.key, val)}
                onKeyDown={handleKeyDown}
                placeholder={slot.placeholder}
                value={currentValue}
              />
            )
          })}
        </div>

        {/* Bottom bar inside box */}
        <div className="border-border/50 mt-2 flex items-center justify-between border-t pt-2.5">
          <div className="flex items-center gap-2">
            <ModelSelectorDropdown />
            <Button
              className="text-muted-foreground hover:text-foreground h-6 gap-1 px-2 text-xs"
              onClick={() => setShowPreview((prev) => !prev)}
              size="sm"
              type="button"
              variant="ghost"
            >
              <Eye className="h-3 w-3" />
              <span>Preview Prompt</span>
              {showPreview ? (
                <ChevronUp className="h-3 w-3" />
              ) : (
                <ChevronDown className="h-3 w-3" />
              )}
            </Button>
          </div>

          <Button
            className="h-8 gap-1.5 px-3 text-xs"
            disabled={!isComplete || disabled || !isModelSelected}
            onClick={handleSend}
            size="sm"
            type="button"
          >
            <Send className="h-3.5 w-3.5" />
            <span>Send</span>
          </Button>
        </div>
      </div>

      {/* Collapsible Prompt Preview */}
      {showPreview && (
        <div className="border-border/60 bg-muted/30 animate-in fade-in slide-in-from-top-1 rounded-lg border p-3 text-xs duration-200">
          <div className="text-muted-foreground border-border/40 mb-2 flex items-center justify-between border-b pb-1.5 font-semibold">
            <span className="flex items-center gap-1.5">
              <Sparkles className="text-primary h-3.5 w-3.5" />
              Generated Agent Prompt
            </span>
            <span className="text-muted-foreground/70 font-mono text-[10px] tracking-wider uppercase">
              Compiled
            </span>
          </div>
          <pre className="text-foreground/90 max-h-48 overflow-y-auto font-mono text-xs leading-relaxed whitespace-pre-wrap">
            {compiledPrompt || '(Fill in the required slots above to compile the prompt)'}
          </pre>
        </div>
      )}
    </div>
  )
}

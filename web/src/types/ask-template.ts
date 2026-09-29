export interface AskTemplate {
  compilePrompt: (values: Record<string, string | undefined>) => string
  description: string
  icon: string
  id: string
  segments: TemplateSegment[]
  title: string
}

type SlotControl = 'expandable-text' | 'inline-text' | 'repo-select'

interface SlotSegment {
  control: SlotControl
  key: string
  optional?: boolean
  placeholder?: string
  type: 'slot'
}

type TemplateSegment = SlotSegment | TextSegment

interface TextSegment {
  type: 'text'
  value: string
}

export interface AskTemplate {
  compilePrompt: (values: Record<string, string | undefined>) => string
  description: string
  icon: string
  id: string
  segments: TemplateSegment[]
  title: string
}

export type SlotControl = 'expandable-text' | 'inline-text' | 'repo-select'

export interface SlotSegment {
  control: SlotControl
  key: string
  optional?: boolean
  placeholder?: string
  type: 'slot'
}

export type TemplateSegment = SlotSegment | TextSegment

export interface TextSegment {
  type: 'text'
  value: string
}

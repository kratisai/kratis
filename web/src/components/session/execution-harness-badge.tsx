import {
  Blocks,
  Code2,
  Cpu,
  Feather,
  FlaskConical,
  Hammer,
  type LucideIcon,
  Sparkles,
  Stars,
  Terminal,
  Wind,
  Zap,
} from 'lucide-react'

import { Badge } from '@/components/ui/badge'

import { AgentBrandIcon } from './agent-brand-icon'

type HarnessKey = string

const HARNESS_ICON: Record<string, LucideIcon> = {
  AIDER: Hammer,
  CLAUDE_CODE: Sparkles,
  CODEX: Code2,
  GEMINI: Stars,
  GOOSE: Wind,
  MISTRAL: Zap,
  OPENCODE: Terminal,
  OPENHANDS: Blocks,
  PI: Feather,
  QWEN: FlaskConical,
}

const HARNESS_LABEL: Record<string, string> = {
  AIDER: 'Aider',
  CLAUDE_CODE: 'Claude Code',
  CODEX: 'Codex',
  GEMINI: 'Gemini',
  GOOSE: 'Goose',
  MISTRAL: 'Mistral',
  OPENCODE: 'OpenCode',
  OPENHANDS: 'OpenHands',
  PI: 'PI',
  QWEN: 'Qwen',
}

const FALLBACK_ICON: LucideIcon = Cpu

interface ExecutionHarnessBadgeProps {
  harness: string
}

export function ExecutionHarnessBadge({ harness }: ExecutionHarnessBadgeProps) {
  const label = harnessDisplayName(harness)
  return (
    <Badge className="gap-1.5" variant="outline">
      <AgentBrandIcon className="h-3 w-3" harness={harness} />
      {label}
    </Badge>
  )
}

export function harnessDisplayName(harness: HarnessKey): string {
  return HARNESS_LABEL[harness] ?? harness
}

export function harnessIcon(harness: HarnessKey): LucideIcon {
  return HARNESS_ICON[harness] ?? FALLBACK_ICON
}

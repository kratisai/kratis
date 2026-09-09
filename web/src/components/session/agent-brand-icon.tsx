import { Blocks, Bot, Feather, FlaskConical, Hammer, Wind, Zap } from 'lucide-react'
import { RiOpenaiFill } from 'react-icons/ri'
import { SiAnthropic, SiGithub, SiGoogle } from 'react-icons/si'

import { cn } from '@/lib/utils'

interface AgentBrandIconProps {
  className?: string
  harness?: string
}

export function AgentBrandIcon({ className, harness }: AgentBrandIconProps) {
  const normalized = (harness || '').toUpperCase().replace(/[- ]/g, '_')

  if (normalized.includes('CLAUDE') || normalized.includes('ANTHROPIC')) {
    return <SiAnthropic aria-label="Anthropic Claude" className={cn('text-[#D97757]', className)} />
  }
  if (
    normalized.includes('OPENAI') ||
    normalized.includes('CODEX') ||
    normalized.includes('OPENCODE') ||
    normalized.includes('GPT')
  ) {
    return <RiOpenaiFill aria-label="OpenAI" className={cn('text-[#10A37F]', className)} />
  }
  if (normalized.includes('GEMINI') || normalized.includes('GOOGLE')) {
    return <SiGoogle aria-label="Google Gemini" className={cn('text-[#4285F4]', className)} />
  }
  if (normalized.includes('GITHUB') || normalized.includes('COPILOT')) {
    return <SiGithub aria-label="GitHub Copilot" className={cn('text-foreground', className)} />
  }
  if (normalized.includes('AIDER')) {
    return <Hammer aria-label="Aider" className={cn('text-amber-500', className)} />
  }
  if (normalized.includes('GOOSE')) {
    return <Wind aria-label="Goose" className={cn('text-teal-500', className)} />
  }
  if (normalized.includes('MISTRAL')) {
    return <Zap aria-label="Mistral" className={cn('text-orange-500', className)} />
  }
  if (normalized.includes('OPENHANDS')) {
    return <Blocks aria-label="OpenHands" className={cn('text-blue-500', className)} />
  }
  if (normalized.includes('PI')) {
    return <Feather aria-label="Pi" className={cn('text-purple-500', className)} />
  }
  if (normalized.includes('QWEN')) {
    return <FlaskConical aria-label="Qwen" className={cn('text-indigo-500', className)} />
  }

  return <Bot aria-label="Agent" className={cn('text-muted-foreground', className)} />
}

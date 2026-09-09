import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import {
  ExecutionHarnessBadge,
  harnessDisplayName,
  harnessIcon,
} from '@/components/session/execution-harness-badge'

describe('ExecutionHarnessBadge', () => {
  it('renders the display name for a known harness', () => {
    render(<ExecutionHarnessBadge harness="OPENCODE" />)

    expect(screen.getByText('OpenCode')).toBeInTheDocument()
  })

  it('renders the raw harness value for an unknown harness', () => {
    render(<ExecutionHarnessBadge harness="FUTURE_AGENT" />)

    expect(screen.getByText('FUTURE_AGENT')).toBeInTheDocument()
  })

  it('renders a badge for each known harness', () => {
    const harnesses = [
      ['AIDER', 'Aider'],
      ['CLAUDE_CODE', 'Claude Code'],
      ['CODEX', 'Codex'],
      ['GEMINI', 'Gemini'],
      ['GOOSE', 'Goose'],
      ['MISTRAL', 'Mistral'],
      ['OPENCODE', 'OpenCode'],
      ['OPENHANDS', 'OpenHands'],
      ['PI', 'PI'],
      ['QWEN', 'Qwen'],
    ] as const

    for (const [value, label] of harnesses) {
      const { unmount } = render(<ExecutionHarnessBadge harness={value} />)
      expect(screen.getByText(label)).toBeInTheDocument()
      unmount()
    }
  })
})

describe('harnessDisplayName', () => {
  it('returns the branded label for known harnesses', () => {
    expect(harnessDisplayName('CODEX')).toBe('Codex')
    expect(harnessDisplayName('CLAUDE_CODE')).toBe('Claude Code')
  })

  it('falls back to the raw harness key for unknown harnesses', () => {
    expect(harnessDisplayName('UNKNOWN')).toBe('UNKNOWN')
  })
})

describe('harnessIcon', () => {
  it('returns a defined icon for every known harness', () => {
    const keys = [
      'AIDER',
      'CLAUDE_CODE',
      'CODEX',
      'GEMINI',
      'GOOSE',
      'MISTRAL',
      'OPENCODE',
      'OPENHANDS',
      'PI',
      'QWEN',
    ]
    for (const key of keys) {
      expect(harnessIcon(key)).toBeDefined()
    }
  })

  it('returns the fallback icon for an unknown harness', () => {
    expect(harnessIcon('UNKNOWN')).toBe(harnessIcon('UNKNOWN'))
    expect(harnessIcon('UNKNOWN')).toBeDefined()
  })
})

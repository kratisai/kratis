import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { AgentBrandIcon } from '@/components/session/agent-brand-icon'

describe('AgentBrandIcon', () => {
  it('renders Anthropic Claude icon for CLAUDE_CODE', () => {
    render(<AgentBrandIcon harness="CLAUDE_CODE" />)
    expect(screen.getByLabelText('Anthropic Claude')).toBeInTheDocument()
  })

  it('renders OpenAI icon for OPENCODE and CODEX', () => {
    const { rerender } = render(<AgentBrandIcon harness="OPENCODE" />)
    expect(screen.getByLabelText('OpenAI')).toBeInTheDocument()

    rerender(<AgentBrandIcon harness="CODEX" />)
    expect(screen.getByLabelText('OpenAI')).toBeInTheDocument()
  })

  it('renders Google Gemini icon for GEMINI', () => {
    render(<AgentBrandIcon harness="GEMINI" />)
    expect(screen.getByLabelText('Google Gemini')).toBeInTheDocument()
  })

  it('renders GitHub icon for GITHUB', () => {
    render(<AgentBrandIcon harness="GITHUB" />)
    expect(screen.getByLabelText('GitHub Copilot')).toBeInTheDocument()
  })

  it('renders specific harness icons for Aider, Goose, Mistral, OpenHands, Pi, Qwen', () => {
    const { rerender } = render(<AgentBrandIcon harness="AIDER" />)
    expect(screen.getByLabelText('Aider')).toBeInTheDocument()

    rerender(<AgentBrandIcon harness="GOOSE" />)
    expect(screen.getByLabelText('Goose')).toBeInTheDocument()

    rerender(<AgentBrandIcon harness="MISTRAL" />)
    expect(screen.getByLabelText('Mistral')).toBeInTheDocument()

    rerender(<AgentBrandIcon harness="OPENHANDS" />)
    expect(screen.getByLabelText('OpenHands')).toBeInTheDocument()

    rerender(<AgentBrandIcon harness="PI" />)
    expect(screen.getByLabelText('Pi')).toBeInTheDocument()

    rerender(<AgentBrandIcon harness="QWEN" />)
    expect(screen.getByLabelText('Qwen')).toBeInTheDocument()
  })

  it('renders fallback agent icon when harness is undefined or unknown', () => {
    const { rerender } = render(<AgentBrandIcon />)
    expect(screen.getByLabelText('Agent')).toBeInTheDocument()

    rerender(<AgentBrandIcon harness="UNKNOWN_HARNESS" />)
    expect(screen.getByLabelText('Agent')).toBeInTheDocument()
  })
})

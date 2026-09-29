import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { CreateHitlRuleDialog } from '@/components/settings/create-hitl-rule-dialog'

describe('CreateHitlRuleDialog', () => {
  it('renders form fields when open', () => {
    render(
      <CreateHitlRuleDialog onOpenChange={vi.fn()} onSubmit={vi.fn()} open={true} />,
    )

    expect(screen.getByText('Add HITL Rule')).toBeInTheDocument()
    expect(screen.getByLabelText(/command pattern/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /add rule/i })).toBeInTheDocument()
    expect(screen.getByText(/precedence rule/i)).toBeInTheDocument()
  })

  it('validates empty command pattern', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()

    render(
      <CreateHitlRuleDialog onOpenChange={vi.fn()} onSubmit={onSubmit} open={true} />,
    )

    await user.click(screen.getByRole('button', { name: /add rule/i }))

    expect(screen.getByText('Command pattern is required')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('submits valid form data', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()

    render(
      <CreateHitlRuleDialog onOpenChange={vi.fn()} onSubmit={onSubmit} open={true} />,
    )

    const input = screen.getByLabelText(/command pattern/i)
    await user.type(input, 'npm test')

    await user.click(screen.getByRole('button', { name: /add rule/i }))

    expect(onSubmit).toHaveBeenCalledWith({
      action: 'ALLOW',
      commandRoot: 'npm test',
      ruleType: 'EXACT',
    })
  })

  it('calls onOpenChange when cancel is clicked', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()

    render(
      <CreateHitlRuleDialog onOpenChange={onOpenChange} onSubmit={vi.fn()} open={true} />,
    )

    await user.click(screen.getByRole('button', { name: /cancel/i }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('submits a tool kind rule', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()

    render(
      <CreateHitlRuleDialog onOpenChange={vi.fn()} onSubmit={onSubmit} open={true} />,
    )

    await user.click(screen.getByLabelText('Match Type'))
    await user.click(screen.getByRole('option', { name: 'Tool Kind' }))

    expect(screen.queryByLabelText(/command pattern/i)).not.toBeInTheDocument()
    await user.click(screen.getByLabelText('Tool Kind'))
    await user.click(screen.getByRole('option', { name: 'edit' }))

    await user.click(screen.getByRole('button', { name: /add rule/i }))

    expect(onSubmit).toHaveBeenCalledWith({
      action: 'ALLOW',
      commandRoot: 'edit',
      ruleType: 'TOOL_KIND',
    })
  })

  it('validates missing tool kind selection', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()

    render(
      <CreateHitlRuleDialog onOpenChange={vi.fn()} onSubmit={onSubmit} open={true} />,
    )

    await user.click(screen.getByLabelText('Match Type'))
    await user.click(screen.getByRole('option', { name: 'Tool Kind' }))
    await user.click(screen.getByRole('button', { name: /add rule/i }))

    expect(screen.getByText('Tool kind is required')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })
})

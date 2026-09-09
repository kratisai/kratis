import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { CreatePermissionRuleDialog } from '@/components/settings/create-permission-rule-dialog'

describe('CreatePermissionRuleDialog', () => {
  it('renders form fields when open', () => {
    render(
      <CreatePermissionRuleDialog onOpenChange={vi.fn()} onSubmit={vi.fn()} open={true} />,
    )

    expect(screen.getByText('Add Permission Rule')).toBeInTheDocument()
    expect(screen.getByLabelText(/command pattern/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /add rule/i })).toBeInTheDocument()
    expect(screen.getByText(/precedence rule/i)).toBeInTheDocument()
  })

  it('validates empty command pattern', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()

    render(
      <CreatePermissionRuleDialog onOpenChange={vi.fn()} onSubmit={onSubmit} open={true} />,
    )

    await user.click(screen.getByRole('button', { name: /add rule/i }))

    expect(screen.getByText('Command pattern is required')).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('submits valid form data', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()

    render(
      <CreatePermissionRuleDialog onOpenChange={vi.fn()} onSubmit={onSubmit} open={true} />,
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
      <CreatePermissionRuleDialog onOpenChange={onOpenChange} onSubmit={vi.fn()} open={true} />,
    )

    await user.click(screen.getByRole('button', { name: /cancel/i }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

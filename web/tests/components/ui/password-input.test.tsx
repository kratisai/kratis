import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { PasswordInput } from '@/components/ui/password-input'

describe('PasswordInput', () => {
  it('renders a password input by default', () => {
    render(<PasswordInput aria-label="Password" defaultValue="secret" />)

    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password')
    expect(
      screen.getByRole('button', { name: 'Show password' }),
    ).toBeInTheDocument()
  })

  it('reveals the value when the toggle is clicked', async () => {
    const user = userEvent.setup()
    render(<PasswordInput aria-label="Password" defaultValue="secret" />)

    const input = screen.getByLabelText('Password')
    await user.click(screen.getByRole('button', { name: 'Show password' }))

    expect(input).toHaveAttribute('type', 'text')
    expect(input).toHaveValue('secret')
    expect(
      screen.getByRole('button', { name: 'Hide password' }),
    ).toBeInTheDocument()
  })

  it('hides the value when the toggle is clicked again', async () => {
    const user = userEvent.setup()
    render(<PasswordInput aria-label="Password" defaultValue="secret" />)

    const input = screen.getByLabelText('Password')
    await user.click(screen.getByRole('button', { name: 'Show password' }))
    await user.click(screen.getByRole('button', { name: 'Hide password' }))

    expect(input).toHaveAttribute('type', 'password')
  })

  it('forwards props to the underlying input', () => {
    render(<PasswordInput aria-label="Password" disabled id="pw" required />)

    const input = screen.getByLabelText('Password')
    expect(input).toHaveAttribute('id', 'pw')
    expect(input).toBeDisabled()
    expect(input).toBeRequired()
  })
})

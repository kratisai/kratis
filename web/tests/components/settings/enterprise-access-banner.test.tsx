import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { EnterpriseAccessBanner } from '@/components/settings/enterprise-access-banner'

describe('EnterpriseAccessBanner', () => {
  it('renders enterprise title, description, and request button', () => {
    render(<EnterpriseAccessBanner onRequestAccess={vi.fn()} />)

    expect(
      screen.getByRole('heading', { name: /kratis enterprise/i }),
    ).toBeInTheDocument()
    expect(
      screen.getByText(
        /scale kratis across your engineering team with dedicated vpc sandboxes, saml\/sso, custom rbac, and priority sla\./i,
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /request enterprise access/i }),
    ).toBeInTheDocument()
  })

  it('calls onRequestAccess when the button is clicked', async () => {
    const handleRequestAccess = vi.fn()
    const user = userEvent.setup()

    render(<EnterpriseAccessBanner onRequestAccess={handleRequestAccess} />)

    const button = screen.getByRole('button', { name: /request enterprise access/i })
    await user.click(button)

    expect(handleRequestAccess).toHaveBeenCalledTimes(1)
  })
})

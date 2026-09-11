import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { EnquiriesBanner } from '@/components/settings/enquiries-banner'

describe('EnquiriesBanner', () => {
  it('renders title, description, and enquiry button', () => {
    render(<EnquiriesBanner onEnquire={vi.fn()} />)

    expect(screen.getByRole('heading', { name: /get in touch/i })).toBeInTheDocument()
    expect(
      screen.getByText(
        /let us know your use case, or discuss paid options for support or features\./i,
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /send an enquiry/i })).toBeInTheDocument()
  })

  it('calls onEnquire when the button is clicked', async () => {
    const handleEnquire = vi.fn()
    const user = userEvent.setup()

    render(<EnquiriesBanner onEnquire={handleEnquire} />)

    const button = screen.getByRole('button', { name: /send an enquiry/i })
    await user.click(button)

    expect(handleEnquire).toHaveBeenCalledTimes(1)
  })
})

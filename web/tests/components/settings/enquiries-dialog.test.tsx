import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { EnquiriesDialog } from '@/components/settings/enquiries-dialog'
import { useInstallationInfo } from '@/hooks/use-config'
import { useAuthStore } from '@/store/auth-store'

vi.mock('@/hooks/use-config', () => ({
  useInstallationInfo: vi.fn().mockReturnValue({
    data: { installId: 'inst-999-abc', version: '1.2.3' },
  }),
}))

describe('EnquiriesDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({
      currentTeamId: 'team-1',
      isAuthenticated: true,
      user: {
        email: 'developer@example.com',
        id: 'user-1',
        name: 'Jane Doe',
      },
    })
  })

  it('renders correctly when open with title, description, and external link', () => {
    render(<EnquiriesDialog onOpenChange={vi.fn()} open={true} />)

    expect(screen.getByRole('heading', { name: /send an enquiry/i })).toBeInTheDocument()
    expect(
      screen.getByText(
        /tell us your use case\. we can also discuss paid options for support or features\./i,
      ),
    ).toBeInTheDocument()

    const openInNewTab = screen.getByRole('link', { name: /open in new tab/i })
    expect(openInNewTab).toBeInTheDocument()
    expect(openInNewTab).toHaveAttribute('href', expect.stringContaining('https://tally.so/r/'))
    expect(openInNewTab).toHaveAttribute('target', '_blank')
    expect(openInNewTab).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('embeds Tally iframe with user email, name, and install_id pre-filled', () => {
    render(<EnquiriesDialog onOpenChange={vi.fn()} open={true} />)

    const iframe = screen.getByTestId('tally-iframe')
    expect(iframe).toBeInTheDocument()
    expect(iframe).toHaveAttribute('title', 'Send an enquiry')

    const src = iframe.getAttribute('src') || ''
    expect(src).toContain('https://tally.so/embed/')
    expect(src).toContain('email=developer%40example.com')
    expect(src).toContain('name=Jane+Doe')
    expect(src).toContain('install_id=inst-999-abc')
    expect(src).toContain('transparentBackground=1')
  })

  it('uses custom formId when passed as prop', () => {
    render(<EnquiriesDialog formId="custom-form-123" onOpenChange={vi.fn()} open={true} />)

    const iframe = screen.getByTestId('tally-iframe')
    expect(iframe.getAttribute('src')).toContain('https://tally.so/embed/custom-form-123')

    const link = screen.getByRole('link', { name: /open in new tab/i })
    expect(link).toHaveAttribute('href', 'https://tally.so/r/custom-form-123')
  })

  it('shows loading spinner until iframe onLoad fires', () => {
    render(<EnquiriesDialog onOpenChange={vi.fn()} open={true} />)

    expect(screen.getByTestId('tally-loading')).toBeInTheDocument()

    const iframe = screen.getByTestId('tally-iframe')
    fireEvent.load(iframe)

    expect(screen.queryByTestId('tally-loading')).not.toBeInTheDocument()
  })

  it('calls onOpenChange when closed', async () => {
    const onOpenChange = vi.fn()
    const user = userEvent.setup()

    render(<EnquiriesDialog onOpenChange={onOpenChange} open={true} />)

    const closeButton = screen.getByRole('button', { name: /close/i })
    await user.click(closeButton)

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('omits email, name, and install_id when they are absent', () => {
    useAuthStore.setState({
      currentTeamId: 'team-1',
      isAuthenticated: true,
      user: null,
    })
    vi.mocked(useInstallationInfo).mockReturnValue({ data: undefined } as never)

    render(<EnquiriesDialog onOpenChange={vi.fn()} open={true} />)

    const src = screen.getByTestId('tally-iframe').getAttribute('src') || ''
    expect(src).not.toContain('email=')
    expect(src).not.toContain('name=')
    expect(src).not.toContain('install_id=')
  })
})

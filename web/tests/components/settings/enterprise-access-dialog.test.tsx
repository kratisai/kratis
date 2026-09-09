import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { EnterpriseAccessDialog } from '@/components/settings/enterprise-access-dialog'
import { useAuthStore } from '@/store/auth-store'

vi.mock('@/hooks/use-config', () => ({
  useInstallationInfo: vi.fn().mockReturnValue({
    data: { installId: 'inst-999-abc', version: '1.2.3' },
  }),
}))

describe('EnterpriseAccessDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({
      currentTeamId: 'team-1',
      isAuthenticated: true,
      user: {
        email: 'developer@enterprise.com',
        id: 'user-1',
        name: 'Jane Doe',
      },
    })
  })

  it('renders correctly when open with title, description, and external link', () => {
    render(<EnterpriseAccessDialog onOpenChange={vi.fn()} open={true} />)

    expect(screen.getByRole('heading', { name: /request enterprise access/i })).toBeInTheDocument()
    expect(
      screen.getByText(/scale kratis across your engineering team/i),
    ).toBeInTheDocument()

    const openInNewTab = screen.getByRole('link', { name: /open in new tab/i })
    expect(openInNewTab).toBeInTheDocument()
    expect(openInNewTab).toHaveAttribute('href', expect.stringContaining('https://tally.so/r/'))
    expect(openInNewTab).toHaveAttribute('target', '_blank')
    expect(openInNewTab).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('embeds Tally iframe with user email, name, and install_id pre-filled', () => {
    render(<EnterpriseAccessDialog onOpenChange={vi.fn()} open={true} />)

    const iframe = screen.getByTestId('tally-iframe')
    expect(iframe).toBeInTheDocument()
    expect(iframe).toHaveAttribute('title', 'Request Enterprise Access')

    const src = iframe.getAttribute('src') || ''
    expect(src).toContain('https://tally.so/embed/')
    expect(src).toContain('email=developer%40enterprise.com')
    expect(src).toContain('name=Jane+Doe')
    expect(src).toContain('install_id=inst-999-abc')
    expect(src).toContain('transparentBackground=1')
  })

  it('uses custom formId when passed as prop', () => {
    render(
      <EnterpriseAccessDialog formId="custom-form-123" onOpenChange={vi.fn()} open={true} />,
    )

    const iframe = screen.getByTestId('tally-iframe')
    expect(iframe.getAttribute('src')).toContain('https://tally.so/embed/custom-form-123')

    const link = screen.getByRole('link', { name: /open in new tab/i })
    expect(link).toHaveAttribute('href', 'https://tally.so/r/custom-form-123')
  })

  it('shows loading spinner until iframe onLoad fires', () => {
    render(<EnterpriseAccessDialog onOpenChange={vi.fn()} open={true} />)

    expect(screen.getByTestId('tally-loading')).toBeInTheDocument()

    const iframe = screen.getByTestId('tally-iframe')
    fireEvent.load(iframe)

    expect(screen.queryByTestId('tally-loading')).not.toBeInTheDocument()
  })

  it('calls onOpenChange when closed', async () => {
    const onOpenChange = vi.fn()
    const user = userEvent.setup()

    render(<EnterpriseAccessDialog onOpenChange={onOpenChange} open={true} />)

    const closeButton = screen.getByRole('button', { name: /close/i })
    await user.click(closeButton)

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { TerminalLogNotice } from '@/components/views/terminal-log-notice'

describe('TerminalLogNotice', () => {
  it('renders nothing for a connected or unknown status', () => {
    const { container, rerender } = render(
      <TerminalLogNotice isWaking={false} onWake={null} status="CONNECTED" />,
    )
    expect(container).toBeEmptyDOMElement()

    rerender(<TerminalLogNotice isWaking={false} onWake={null} status={undefined} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('offers a wake action for a sleeping sandbox', async () => {
    const onWake = vi.fn()
    render(<TerminalLogNotice isWaking={false} onWake={onWake} status="SLEEPING" />)

    await userEvent.click(screen.getByRole('button', { name: /wake sandbox to view console/i }))

    expect(onWake).toHaveBeenCalledTimes(1)
  })

  it('disables the wake action while waking', () => {
    render(<TerminalLogNotice isWaking onWake={vi.fn()} status="SLEEPING" />)

    expect(screen.getByRole('button', { name: /wake sandbox to view console/i })).toBeDisabled()
  })

  it('omits the wake action when the environment is unknown', () => {
    render(<TerminalLogNotice isWaking={false} onWake={null} status="SLEEPING" />)

    expect(screen.getByText('Sandbox is asleep.')).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('shows a terminated notice with no wake action', () => {
    render(<TerminalLogNotice isWaking={false} onWake={vi.fn()} status="TERMINATED" />)

    expect(screen.getByTestId('terminal-terminated-notice')).toHaveTextContent(/terminated/i)
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('shows a starting notice while the sandbox reconnects', () => {
    render(<TerminalLogNotice isWaking={false} onWake={null} status="PENDING_RECONNECT" />)

    expect(screen.getByTestId('terminal-pending-reconnect-notice')).toBeInTheDocument()
    expect(screen.getByTestId('terminal-pending-reconnect-notice')).toHaveTextContent(/starting/i)
  })

  it('shows a disconnected notice for DISCONNECTED', () => {
    render(<TerminalLogNotice isWaking={false} onWake={null} status="DISCONNECTED" />)

    expect(screen.getByTestId('terminal-disconnected-notice')).toBeInTheDocument()
  })
})

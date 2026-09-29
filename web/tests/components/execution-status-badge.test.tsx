import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { ExecutionStatusBadge } from '@/components/session/execution-status-badge'

describe('ExecutionStatusBadge', () => {
  it('renders an Active badge for a running execution', () => {
    render(<ExecutionStatusBadge status="RUNNING" />)

    expect(screen.getByText('Active')).toBeInTheDocument()
  })

  it('renders a Complete badge for a completed execution', () => {
    render(<ExecutionStatusBadge status="COMPLETED" />)

    expect(screen.getByText('Complete')).toBeInTheDocument()
  })

  it('renders a Complete badge for an idle execution', () => {
    render(<ExecutionStatusBadge status="IDLE" />)

    expect(screen.getByText('Complete')).toBeInTheDocument()
  })

  it('renders a Failed badge for a failed execution', () => {
    render(<ExecutionStatusBadge status="FAILED" />)

    expect(screen.getByText('Failed')).toBeInTheDocument()
  })
})

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { IngestionStatusBadge } from '@/components/repos/ingestion-status-badge'

describe('IngestionStatusBadge', () => {
  it('renders "Not Ingested" badge when status is undefined', () => {
    render(<IngestionStatusBadge status={undefined} />)
    expect(screen.getByText('Not Ingested')).toBeTruthy()
  })

  it('renders "Not Ingested" badge when status is null', () => {
    render(<IngestionStatusBadge status={null} />)
    expect(screen.getByText('Not Ingested')).toBeTruthy()
  })

  it('renders QUEUED status with correct styling', () => {
    render(<IngestionStatusBadge status="QUEUED" />)
    const badge = screen.getByText('Queued')
    expect(badge).toBeTruthy()
    expect(badge.closest('[class*="bg-yellow"]')).toBeTruthy()
  })

  it('renders QUEUED status with queue position', () => {
    render(<IngestionStatusBadge queuePosition={2} status="QUEUED" />)
    const badge = screen.getByText('Queued (#2)')
    expect(badge).toBeTruthy()
    expect(badge.closest('[class*="bg-yellow"]')).toBeTruthy()
  })

  it('renders PROCESSING status with correct styling', () => {
    render(<IngestionStatusBadge status="PROCESSING" />)
    const badge = screen.getByText('Processing')
    expect(badge).toBeTruthy()
    expect(badge.closest('[class*="bg-blue"]')).toBeTruthy()
  })

  it('renders SUCCESS status with correct styling', () => {
    render(<IngestionStatusBadge status="SUCCESS" />)
    const badge = screen.getByText('Success')
    expect(badge).toBeTruthy()
    expect(badge.closest('[class*="bg-green"]')).toBeTruthy()
  })

  it('renders FAILED status with correct styling', () => {
    render(<IngestionStatusBadge status="FAILED" />)
    const badge = screen.getByText('Failed')
    expect(badge).toBeTruthy()
    expect(badge.closest('[class*="bg-red"]')).toBeTruthy()
  })
})

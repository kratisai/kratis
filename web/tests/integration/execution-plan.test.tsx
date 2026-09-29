import { fireEvent } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'

import { ExecutionActivityLog } from '@/components/session/execution-activity-log'
import { useActivityStore } from '@/store/activity-store'

import { setupFetchMock } from '../support/test-fetch-mocks'
import { renderWithProviders, screen, waitFor } from '../support/test-render'
import { setupConnected, triggerMockExecutionActivity } from '../support/test-websocket'

const EXECUTION_ID = 'exec-1'

describe('Execution Plan Activity', () => {
  setupFetchMock()

  beforeEach(() => {
    useActivityStore.setState({ activitiesByExecution: {} })
  })

  it('renders each plan update as a fresh, expanded activity in the log', async () => {
    const ws = setupConnected()
    triggerMockExecutionActivity(ws, EXECUTION_ID, 'PLAN', 'Agent plan updated', 'in_progress', 'plan-1', {
      plan: [
        { content: 'Setup repo', priority: 'high', status: 'in_progress' },
        { content: 'Run tests', priority: 'low', status: 'pending' },
      ],
    })

    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    // The first plan activity is expanded by default: both entries are visible.
    await waitFor(() => {
      expect(screen.getByText('Setup repo')).toBeInTheDocument()
    })
    expect(screen.getByText('Run tests')).toBeInTheDocument()

    // A second update creates a NEW plan activity (its own history entry);
    // the first snapshot stays visible as a completed, expanded record.
    triggerMockExecutionActivity(ws, EXECUTION_ID, 'PLAN', 'Agent plan updated', 'in_progress', 'plan-2', {
      plan: [{ content: 'Run tests', priority: 'low', status: 'completed' }],
    })

    await waitFor(() => {
      expect(screen.getAllByText('Run tests')).toHaveLength(2)
    })
    expect(screen.getByText('Setup repo')).toBeInTheDocument()
    expect(screen.getAllByText('All Done')).toHaveLength(1)
  })

  it('lets the user collapse and re-expand a plan activity', async () => {
    const ws = setupConnected()
    triggerMockExecutionActivity(ws, EXECUTION_ID, 'PLAN', 'Agent plan updated', 'in_progress', 'plan-1', {
      plan: [{ content: 'Setup repo', priority: 'high', status: 'in_progress' }],
    })

    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    await waitFor(() => {
      expect(screen.getByText('Setup repo')).toBeInTheDocument()
    })

    // Collapse hides the checklist; re-expanding restores it.
    fireEvent.click(screen.getByText('Plan'))
    expect(screen.queryByText('Setup repo')).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('Plan'))
    expect(screen.getByText('Setup repo')).toBeInTheDocument()
  })
})

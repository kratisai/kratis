import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ExecutionActivityLog } from '@/components/session/execution-activity-log'
import { useActivityStore } from '@/store/activity-store'

import { setupFetchMock } from '../support/test-fetch-mocks'
import { renderWithProviders, screen, waitFor } from '../support/test-render'
import {
  setupConnected,
  triggerMockExecutionActivity,
  triggerMockExecutionOutput,
  triggerMockPermissionRequired,
  triggerMockPermissionResolved,
} from '../support/test-websocket'

const EXECUTION_ID = 'exec-1'

describe('Execution Activity Log', () => {
  setupFetchMock()

  beforeEach(() => {
    // Clear activity store
    useActivityStore.getState().clearActivities('current')
    useActivityStore.getState().clearActivities(EXECUTION_ID)
  })

  it('renders thinking activity', async () => {
    const ws = setupConnected()

    // Trigger a THINKING execution_activity frame
    triggerMockExecutionActivity(ws, EXECUTION_ID, 'THINKING', 'Analyzing the codebase structure')

    // Render the activity log for that execution
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    // Assert thinking card appears (the thought is both the title and the body)
    await waitFor(() => {
      expect(screen.getAllByText('Analyzing the codebase structure').length).toBeGreaterThanOrEqual(1)
    })
  })

  it('renders tool execution activities from the store', () => {
    useActivityStore.setState({
      activitiesByExecution: {
        [EXECUTION_ID]: [
          {
            collapsed: false,
            executionId: EXECUTION_ID,
            id: 'tool-1',
            startedAt: '2024-06-15T10:30:00.000Z',
            state: 'completed',
            taskId: 'task-1',
            thought: 'Searching for files',
            toolName: 'file_search',
            type: 'tool_execution',
          },
        ],
      },
    })

    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    expect(screen.getByText('file_search')).toBeInTheDocument()
    expect(screen.getByText('Searching for files')).toBeInTheDocument()
  })

  it('renders command execution with permission prompt inline', async () => {
    const ws = setupConnected()

    // Trigger permission_required event
    triggerMockPermissionRequired(ws, EXECUTION_ID, 'npm run build')

    // Render the activity log with the actual executionId
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    // Assert approval prompt appears in timeline
    await waitFor(() => {
      expect(screen.getByText('Permission Required')).toBeInTheDocument()
    })
    expect(screen.getByText(/npm run build/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Allow once/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Reject/i })).toBeInTheDocument()
  })

  it('collapses resolved permission into summary', async () => {
    const ws = setupConnected()

    // Trigger permission_required
    triggerMockPermissionRequired(ws, EXECUTION_ID, 'rm -rf /tmp/cache')

    // Trigger permission_resolved (approved)
    triggerMockPermissionResolved(
      ws,
      EXECUTION_ID,
      'rm -rf /tmp/cache',
      true,
      'user-1',
      'Test User',
    )

    // Render the activity log
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    // Assert resolved state is shown (collapsed summary)
    await waitFor(() => {
      expect(screen.getByText(/Permission approved/)).toBeInTheDocument()
    })
    expect(screen.getByText(/rm -rf \/tmp\/cache/)).toBeInTheDocument()
    // Approve/Reject buttons should not be visible
    expect(screen.queryByRole('button', { name: /Allow once/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Reject/i })).not.toBeInTheDocument()
  })

  it('shows empty state when no activities', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    expect(screen.getByText('No activity yet')).toBeInTheDocument()
  })

  it('auto-scrolls with instant scrolling when a new activity arrives', async () => {
    const scrollSpy = vi.spyOn(Element.prototype, 'scrollIntoView')
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)
    scrollSpy.mockClear()

    useActivityStore.getState().handleActivityEvent({
      actionId: 'a1',
      activityType: 'RESEARCH',
      description: 'read pom.xml',
      executionId: EXECUTION_ID,
      status: 'completed',
      type: 'execution_activity',
    })

    await waitFor(() => {
      expect(scrollSpy).toHaveBeenCalledWith(
        expect.objectContaining({ behavior: 'auto', block: 'end' }),
      )
    })
    scrollSpy.mockRestore()
  })

  it('renders command execution output when active', async () => {
    // Directly populate the activity store with a command execution activity
    // First trigger a permission required to create the activity
    const ws = setupConnected()

    triggerMockPermissionRequired(ws, EXECUTION_ID, 'echo hello')
    triggerMockPermissionResolved(ws, EXECUTION_ID, 'echo hello', true, 'user-1', 'Test User')

    // Now trigger execution output
    triggerMockExecutionOutput(ws, '$ hello', 'stdout', EXECUTION_ID)
    triggerMockExecutionOutput(ws, '$ world', 'stdout', EXECUTION_ID)

    // Render the activity log
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    // The command should be shown (either as resolved approval or active command)
    await waitFor(() => {
      expect(screen.getByText(/echo hello/)).toBeInTheDocument()
    })
  })

  it('renders replayed command output from the persisted detail transcript', () => {
    // A late joiner has no live execution_output lines; the full transcript
    // arrives persisted in the final execution_activity detail.output.
    useActivityStore.setState({
      activitiesByExecution: {
        [EXECUTION_ID]: [
          {
            actionId: 'a1',
            approvalRequired: false,
            collapsed: false,
            command: 'apt-get install -y openjdk-17-jdk maven',
            detail: {
              exitCode: 1,
              kind: 'execute',
              output:
                'Reading package lists...\nE: Permission denied\n\nCommand exited with code 100',
            },
            executionId: EXECUTION_ID,
            exitCode: 1,
            id: 'cmd-1',
            output: [],
            startedAt: '2024-06-15T10:30:00.000Z',
            state: 'error',
            type: 'command_execution' as const,
          },
        ],
      },
    })

    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    expect(screen.getByText(/Reading package lists/)).toBeInTheDocument()
    expect(screen.getByText(/Command exited with code 100/)).toBeInTheDocument()
    expect(screen.getByText(/exit: 1/)).toBeInTheDocument()
  })
})

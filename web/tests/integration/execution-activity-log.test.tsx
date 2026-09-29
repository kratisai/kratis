import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ExecutionActivityLog } from '@/components/session/execution-activity-log'
import { useActivityStore } from '@/store/activity-store'

import { addFetchHandler, setupFetchMock } from '../support/test-fetch-mocks'
import { renderWithProviders, screen, waitFor } from '../support/test-render'
import {
  setupConnected,
  triggerMockExecutionActivity,
  triggerMockExecutionOutput,
  triggerMockPermissionRequired,
  triggerMockPermissionResolved,
} from '../support/test-websocket'

const EXECUTION_ID = 'exec-1'

interface ScrollCall {
  el: Element
  options?: boolean | ScrollIntoViewOptions
}

function captureScrollCalls() {
  const calls: ScrollCall[] = []
  const spy = vi
    .spyOn(Element.prototype, 'scrollIntoView')
    .mockImplementation(function (this: Element, options?: boolean | ScrollIntoViewOptions) {
      calls.push({ el: this, options })
    })
  return {
    calls,
    restore: () => {
      spy.mockRestore()
    },
  }
}

/** jsdom has no layout, so metrics must be shadowed to simulate a scrolled-up document. */
function stubDocumentScrollMetrics(metrics: { clientHeight: number; scrollHeight: number; scrollTop: number }) {
  const el = document.documentElement as unknown as Record<string, number>
  for (const prop of ['clientHeight', 'scrollHeight', 'scrollTop'] as const) {
    Object.defineProperty(el, prop, { configurable: true, value: metrics[prop] })
  }
  return () => {
    for (const prop of ['clientHeight', 'scrollHeight', 'scrollTop'] as const) {
      delete el[prop]
    }
  }
}

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

  it('does not yank the view back to the bottom after the user scrolls up', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)
    const { calls, restore } = captureScrollCalls()
    const restoreMetrics = stubDocumentScrollMetrics({
      clientHeight: 200,
      scrollHeight: 5000,
      scrollTop: 0,
    })
    // Recompute the near-bottom state against the stubbed metrics.
    document.documentElement.dispatchEvent(new Event('scroll'))

    try {
      useActivityStore.getState().handleActivityEvent({
        actionId: 'a-scrolled-up',
        activityType: 'RESEARCH',
        description: 'read pom.xml',
        executionId: EXECUTION_ID,
        status: 'in_progress',
        type: 'execution_activity',
      })

      await waitFor(() => {
        expect(screen.getByText('read pom.xml')).toBeInTheDocument()
      })
      expect(calls).toHaveLength(0)
    } finally {
      restore()
      restoreMetrics()
    }
  })

  it('scrolls an approval prompt into view when it merges into the current activity', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)
    const { calls, restore } = captureScrollCalls()

    useActivityStore.getState().handleActivityEvent({
      actionId: 'tc-approve-scroll',
      activityType: 'COMMAND',
      description: 'systemctl restart app',
      executionId: EXECUTION_ID,
      status: 'in_progress',
      type: 'execution_activity',
    })
    await waitFor(() => {
      expect(screen.getByText('systemctl restart app')).toBeInTheDocument()
    })

    // Merges into the existing activity in place — no length change, so only
    // the prompt scroll can bring the card into view.
    useActivityStore.getState().handleHitlRequired({
      command: 'systemctl restart app',
      executionId: EXECUTION_ID,
      hitlId: 'tc-approve-scroll',
      kind: 'approval',
      message: 'Allow systemctl restart app?',
      type: 'execution_hitl_required',
    })

    await waitFor(() => {
      expect(screen.getByText('Permission Required')).toBeInTheDocument()
    })

    const prompt = screen.getByText('Permission Required').closest('[data-activity-id]')
    expect(prompt).not.toBeNull()
    const promptCall = calls.find((call) => call.el === prompt)
    expect(promptCall?.options).toEqual({ behavior: 'auto', block: 'center' })
    restore()
  })

  it('scrolls a question prompt into view when a HITL question arrives', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)
    const { calls, restore } = captureScrollCalls()

    useActivityStore.getState().handleHitlRequired({
      executionId: EXECUTION_ID,
      form: { properties: { target: { type: 'string' } }, type: 'object' },
      hitlId: 'el-scroll',
      kind: 'question',
      message: 'Choose a deployment target',
      type: 'execution_hitl_required',
    })

    await waitFor(() => {
      expect(screen.getByText('Agent Question')).toBeInTheDocument()
    })

    const prompt = screen.getByText('Agent Question').closest('[data-activity-id]')
    expect(prompt).not.toBeNull()
    const promptCall = calls.find((call) => call.el === prompt)
    expect(promptCall?.options).toEqual({ behavior: 'auto', block: 'center' })
    restore()
  })

  it('does not chase the list bottom while a HITL prompt is pending', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)
    const { calls, restore } = captureScrollCalls()

    useActivityStore.getState().handleHitlRequired({
      executionId: EXECUTION_ID,
      form: { properties: { target: { type: 'string' } }, type: 'object' },
      hitlId: 'el-follow',
      kind: 'question',
      message: 'Choose a deployment target',
      type: 'execution_hitl_required',
    })
    await waitFor(() => {
      expect(screen.getByText('Agent Question')).toBeInTheDocument()
    })
    calls.length = 0

    useActivityStore.getState().handleActivityEvent({
      actionId: 'a-follow',
      activityType: 'RESEARCH',
      description: 'read pom.xml',
      executionId: EXECUTION_ID,
      status: 'in_progress',
      type: 'execution_activity',
    })
    await waitFor(() => {
      expect(screen.getByText('read pom.xml')).toBeInTheDocument()
    })
    expect(calls).toHaveLength(0)
    restore()
  })

  it('renders command execution output when active', async () => {
    // Directly populate the activity store with a command execution activity
    // First trigger a permission required to create the activity
    const ws = setupConnected()

    triggerMockPermissionRequired(ws, EXECUTION_ID, 'echo hello')
    triggerMockPermissionResolved(ws, EXECUTION_ID, 'echo hello', true, 'user-1', 'Test User')

    triggerMockExecutionOutput(ws, '$ hello', 'stdout', EXECUTION_ID)
    triggerMockExecutionOutput(ws, '$ world', 'stdout', EXECUTION_ID)

    // Render the activity log
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    // The command should be shown (either as resolved approval or active command)
    await waitFor(() => {
      expect(screen.getByText(/echo hello/)).toBeInTheDocument()
    })
    expect(screen.queryByText(/\$ hello/)).not.toBeInTheDocument()
    expect(screen.queryByText(/\$ world/)).not.toBeInTheDocument()
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

  it('sends optional feedback with the rejection as steering guidance', async () => {
    const ws = setupConnected()
    triggerMockPermissionRequired(ws, EXECUTION_ID, 'npm run build')

    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    await waitFor(() => {
      expect(screen.getByText('Permission Required')).toBeInTheDocument()
    })

    const captured: Array<Record<string, unknown>> = []
    addFetchHandler((url, options) => {
      if (url.includes('/api/v1/hitl/resolve') && options.method === 'POST') {
        captured.push(JSON.parse(String(options.body)) as Record<string, unknown>)
        return new Response(null, { status: 204 })
      }
      return null
    })

    await user.click(screen.getByRole('button', { name: 'Message to agent' }))
    await user.type(
      screen.getByLabelText('Message to agent'),
      'use pnpm instead of npm',
    )
    await user.click(screen.getByRole('button', { name: /Reject/i }))

    await waitFor(() => {
      expect(captured).toHaveLength(1)
    })
    expect(captured[0]).toEqual({
      executionId: EXECUTION_ID,
      feedback: 'use pnpm instead of npm',
      hitlId: 'npm run build',
      optionId: 'reject-once',
      response: 'declined',
    })
  })

  it('sends optional feedback when declining a question', async () => {
    setupConnected()
    useActivityStore.getState().handleHitlRequired({
      executionId: EXECUTION_ID,
      form: { properties: { target: { type: 'string' } }, type: 'object' },
      hitlId: 'el-feedback',
      kind: 'question',
      message: 'Choose a deployment target',
      type: 'execution_hitl_required',
    })

    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    await waitFor(() => {
      expect(screen.getByText('Agent Question')).toBeInTheDocument()
    })

    const captured: Array<Record<string, unknown>> = []
    addFetchHandler((url, options) => {
      if (url.includes('/api/v1/hitl/resolve') && options.method === 'POST') {
        captured.push(JSON.parse(String(options.body)) as Record<string, unknown>)
        return new Response(null, { status: 204 })
      }
      return null
    })

    await user.click(screen.getByRole('button', { name: 'Message to agent' }))
    await user.type(
      screen.getByLabelText('Message to agent'),
      'staging is offline, use preview',
    )
    await user.click(screen.getByRole('button', { name: 'Decline' }))

    await waitFor(() => {
      expect(captured).toHaveLength(1)
    })
    expect(captured[0]).toEqual({
      executionId: EXECUTION_ID,
      feedback: 'staging is offline, use preview',
      hitlId: 'el-feedback',
      response: 'declined',
    })
  })

  it('shows a timed-out approval as unanswered instead of rejected', async () => {
    const ws = setupConnected()
    triggerMockPermissionRequired(ws, EXECUTION_ID, 'rm -rf /tmp/cache')

    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    await waitFor(() => {
      expect(screen.getByText('Permission Required')).toBeInTheDocument()
    })

    triggerMockPermissionResolved(
      ws,
      EXECUTION_ID,
      'rm -rf /tmp/cache',
      false,
      null,
      'System (timeout)',
      'rm -rf /tmp/cache',
      'reject-once',
      'cancelled',
    )

    await waitFor(() => {
      expect(screen.getByText(/Permission request timed out/)).toBeInTheDocument()
    })
    expect(screen.getByText(/no response/)).toBeInTheDocument()
    expect(screen.queryByText(/Permission rejected/)).not.toBeInTheDocument()
  })

  it('shows a user-dismissed approval as cancelled', async () => {
    const ws = setupConnected()
    triggerMockPermissionRequired(ws, EXECUTION_ID, 'rm -rf /tmp/cache')

    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    await waitFor(() => {
      expect(screen.getByText('Permission Required')).toBeInTheDocument()
    })

    triggerMockPermissionResolved(
      ws,
      EXECUTION_ID,
      'rm -rf /tmp/cache',
      false,
      'user-1',
      'Test User',
      'rm -rf /tmp/cache',
      'reject-once',
      'cancelled',
    )

    await waitFor(() => {
      expect(screen.getByText(/Permission request cancelled/)).toBeInTheDocument()
    })
    expect(screen.queryByText(/Permission rejected/)).not.toBeInTheDocument()
  })
})

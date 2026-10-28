import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { Activity } from '@/types/execution-activity-types'

import { ExecutionActivityLog } from '@/components/session/execution-activity-log'
import { useActivityStore } from '@/store/activity-store'

import { setupFetchMock } from '../support/test-fetch-mocks'
import { renderWithProviders, screen, waitFor } from '../support/test-render'

const EXECUTION_ID = 'exec-expanded-1'

function seedActivities(activities: Activity[]) {
  const store = useActivityStore.getState()
  store.clearActivities(EXECUTION_ID)

  useActivityStore.setState((state) => ({
    activitiesByExecution: {
      ...state.activitiesByExecution,
      [EXECUTION_ID]: activities,
    },
    currentExecutionId: EXECUTION_ID,
  }))
}

describe('Execution Activity Log - Expanded/Collapsed States', () => {
  setupFetchMock()

  beforeEach(() => {
    useActivityStore.getState().clearActivities(EXECUTION_ID)
    useActivityStore.getState().clearActivities('current')
  })

  it('renders completed command execution auto-collapsed with the command title', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        approvalRequired: false,
        collapsed: true,
        command: 'npm run build',
        detail: { output: 'Building project...\nBuild complete' },
        endedAt: '2024-06-15T10:31:00.000Z',
        executionId: EXECUTION_ID,
        exitCode: 0,
        id: 'cmd-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        type: 'command_execution',
      },
    ])

    await waitFor(() => {
      expect(screen.getByText('npm run build')).toBeInTheDocument()
    })
    expect(screen.queryByText(/Building project/)).not.toBeInTheDocument()

    const expandButton = screen.getByText('npm run build').closest('button')!
    await user.click(expandButton)

    await waitFor(() => {
      expect(screen.getByText(/Building project\.\.\./)).toBeInTheDocument()
    })
    expect(screen.getByText(/Build complete/)).toBeInTheDocument()
  })

  it('renders error command execution auto-collapsed with X icon', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        approvalRequired: false,
        collapsed: true,
        command: 'rm -rf /tmp/cache',
        detail: { output: 'Permission denied' },
        endedAt: '2024-06-15T10:31:00.000Z',
        executionId: EXECUTION_ID,
        exitCode: 1,
        id: 'cmd-err-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'error',
        type: 'command_execution',
      },
    ])

    await waitFor(() => {
      expect(screen.getByText('rm -rf /tmp/cache')).toBeInTheDocument()
    })
    expect(screen.queryByText('Permission denied')).not.toBeInTheDocument()

    const expandButton = screen.getByText('rm -rf /tmp/cache').closest('button')!
    await user.click(expandButton)

    await waitFor(() => {
      expect(screen.getByText('Permission denied')).toBeInTheDocument()
    })
  })

  it('renders active command execution in expanded state with output', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        approvalRequired: false,
        collapsed: false,
        command: 'npm test',
        detail: { output: 'Test suite running...\nPASS src/app.test.ts' },
        executionId: EXECUTION_ID,
        id: 'cmd-active-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'active',
        type: 'command_execution',
      },
    ])

    await waitFor(() => {
      expect(screen.getByText('npm test')).toBeInTheDocument()
    })

    expect(screen.getByText(/Test suite running\.\.\./)).toBeInTheDocument()
    expect(screen.getByText(/PASS src\/app\.test\.ts/)).toBeInTheDocument()
  })

  it('renders completed thinking activity collapsed to a "Thinking" single-line summary', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        collapsed: true,
        executionId: EXECUTION_ID,
        id: 'think-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        thought: 'I need to analyze the project structure first.',
        type: 'thinking',
      },
    ])

    const summary = await screen.findByText(
      'Thinking ... I need to analyze the project structure first.',
    )
    expect(summary.className).toContain('truncate')
    expect(summary.className).toContain('italic')

    await user.click(summary.closest('button')!)

    await waitFor(() => {
      const expanded = screen.getByText(
        'Thinking ... I need to analyze the project structure first.',
      )
      expect(expanded.className).not.toContain('truncate')
      expect(expanded.className).toContain('whitespace-pre-wrap')
    })
  })

  it('renders active thinking activity expanded with a "Thinking" prefix and spinner', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        collapsed: false,
        executionId: EXECUTION_ID,
        id: 'think-active-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'active',
        thought: 'Processing the request...',
        type: 'thinking',
      },
    ])

    await waitFor(() => {
      expect(screen.getByText('Thinking ... Processing the request...')).toBeInTheDocument()
    })
  })

  it('collapses a multi-line thought into a single-line "Thinking" summary', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        collapsed: true,
        executionId: EXECUTION_ID,
        id: 'think-multiline-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        thought: 'Searching for the failing test.\nFound it in src/app.test.ts',
        type: 'thinking',
      },
    ])

    const summary = await screen.findByText(
      'Thinking ... Searching for the failing test. Found it in src/app.test.ts',
    )
    expect(summary.className).toContain('truncate')
    expect(summary.textContent).not.toContain('\n')
  })

  it('renders completed tool execution auto-collapsed with the tool title', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        collapsed: true,
        endedAt: '2024-06-15T10:31:00.000Z',
        executionId: EXECUTION_ID,
        id: 'tool-1',
        result: 'Found 42 files',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        taskId: 'task-1',
        thought: 'Searching for TypeScript files',
        toolName: 'file_search',
        type: 'tool_execution',
      },
    ])

    await waitFor(() => {
      expect(screen.getByText('file_search')).toBeInTheDocument()
    })
    expect(screen.queryByText('Found 42 files')).not.toBeInTheDocument()

    const expandButton = screen.getByText('file_search').closest('button')!
    await user.click(expandButton)

    await waitFor(() => {
      expect(screen.getByText('Found 42 files')).toBeInTheDocument()
    })
  })

  it('renders error tool execution auto-collapsed with X icon', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        collapsed: true,
        endedAt: '2024-06-15T10:31:00.000Z',
        error: 'File not found',
        executionId: EXECUTION_ID,
        id: 'tool-err-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'error',
        taskId: 'task-2',
        thought: 'Trying to read config',
        toolName: 'read_file',
        type: 'tool_execution',
      },
    ])

    await waitFor(() => {
      expect(screen.getByText('read_file')).toBeInTheDocument()
    })
    expect(screen.queryByText('File not found')).not.toBeInTheDocument()

    const expandButton = screen.getByText('read_file').closest('button')!
    await user.click(expandButton)

    await waitFor(() => {
      expect(screen.getByText('File not found')).toBeInTheDocument()
    })
  })

  it('renders active tool execution expanded with thought', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        collapsed: false,
        executionId: EXECUTION_ID,
        id: 'tool-active-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'active',
        taskId: 'task-3',
        thought: 'Analyzing dependencies',
        toolName: 'web_search',
        type: 'tool_execution',
      },
    ])

    await waitFor(() => {
      expect(screen.getByText('web_search')).toBeInTheDocument()
    })
    expect(screen.getByText('Analyzing dependencies')).toBeInTheDocument()
  })

  it('collapses the active (current) command via its header and re-expands it', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        approvalRequired: false,
        collapsed: false,
        command: 'echo hello',
        detail: { output: 'hello' },
        executionId: EXECUTION_ID,
        id: 'cmd-toggle-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'active',
        type: 'command_execution',
      },
      {
        approvalRequired: false,
        collapsed: false,
        command: 'echo done',
        detail: { output: 'done' },
        endedAt: '2024-06-15T10:31:00.000Z',
        executionId: EXECUTION_ID,
        exitCode: 0,
        id: 'cmd-toggle-2',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        type: 'command_execution',
      },
    ])

    await waitFor(() => {
      expect(screen.getByText('echo hello')).toBeInTheDocument()
    })
    expect(screen.getByText('hello')).toBeInTheDocument()

    const activeHeader = screen.getByText('echo hello').closest('button')!
    await user.click(activeHeader)
    expect(screen.queryByText('hello')).not.toBeInTheDocument()

    await user.click(screen.getByText('echo hello').closest('button')!)
    expect(screen.getByText('hello')).toBeInTheDocument()

    const doneHeader = screen.getByText('echo done').closest('button')!
    await user.click(doneHeader)
    expect(screen.queryByText('done')).not.toBeInTheDocument()

    await user.click(screen.getByText('echo done').closest('button')!)
    expect(screen.getByText('done')).toBeInTheDocument()
  })

  it('keeps a manually collapsed active command collapsed while its stream continues', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    useActivityStore.getState().handleActivityEvent({
      actionId: 'cmd-stream-1',
      activityType: 'COMMAND',
      createdAt: '2026-10-20T17:32:12.672Z',
      description: 'npm test',
      executionId: EXECUTION_ID,
      status: 'in_progress',
      type: 'execution_activity',
    })
    await waitFor(() => {
      expect(screen.getByText('npm test')).toBeInTheDocument()
    })

    useActivityStore.getState().handleActivityEvent({
      actionId: 'cmd-stream-1',
      activityType: 'COMMAND',
      createdAt: '2026-10-20T17:32:12.672Z',
      description: 'npm test',
      detail: { output: 'PASS src/a.test.ts' },
      executionId: EXECUTION_ID,
      status: 'in_progress',
      type: 'execution_activity',
    })
    await waitFor(() => {
      expect(screen.getByText('PASS src/a.test.ts')).toBeInTheDocument()
    })

    await user.click(screen.getByText('npm test').closest('button')!)
    expect(screen.queryByText('PASS src/a.test.ts')).not.toBeInTheDocument()

    useActivityStore.getState().handleActivityEvent({
      actionId: 'cmd-stream-1',
      activityType: 'COMMAND',
      createdAt: '2026-10-20T17:32:12.672Z',
      description: 'npm test',
      detail: { output: 'PASS src/a.test.ts\nPASS src/b.test.ts' },
      executionId: EXECUTION_ID,
      status: 'in_progress',
      type: 'execution_activity',
    })

    expect(screen.queryByText('PASS src/a.test.ts')).not.toBeInTheDocument()
    expect(screen.queryByText(/PASS src\/b\.test\.ts/)).not.toBeInTheDocument()
  })

  it('auto-collapses a completed agent message once the next activity arrives', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    useActivityStore.getState().handleActivityEvent({
      actionId: 'msg-1',
      activityType: 'MESSAGE',
      createdAt: '2026-10-20T17:32:12.672Z',
      description: 'Here is the result',
      detail: { messageId: 'msg-1', role: 'agent' },
      executionId: EXECUTION_ID,
      status: 'in_progress',
      type: 'execution_activity',
    })
    await waitFor(() => {
      const open = screen.getByText('Here is the result')
      expect(open.className).toContain('text-sm')
      expect(open.className).toContain('whitespace-pre-wrap')
    })
    expect(screen.queryByText('Agent')).not.toBeInTheDocument()

    useActivityStore.getState().handleActivityEvent({
      actionId: 'msg-1',
      activityType: 'MESSAGE',
      createdAt: '2026-10-20T17:32:12.672Z',
      description: 'Here is the result',
      detail: { messageId: 'msg-1', role: 'agent' },
      executionId: EXECUTION_ID,
      status: 'completed',
      type: 'execution_activity',
    })

    useActivityStore.getState().handleActivityEvent({
      actionId: 'tc-1',
      activityType: 'RESEARCH',
      createdAt: '2026-10-20T17:32:12.672Z',
      description: 'Reading main.go',
      executionId: EXECUTION_ID,
      status: 'in_progress',
      type: 'execution_activity',
    })

    await waitFor(() => {
      expect(screen.getByText('Reading main.go')).toBeInTheDocument()
    })
    await waitFor(() => {
      const summary = screen.getByText('Here is the result')
      expect(summary.className).toContain('truncate')
    })
    expect(screen.queryByText('Agent')).not.toBeInTheDocument()
  })

  it('renders a command record from detail.output only', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    useActivityStore.getState().handleActivityEvent({
      actionId: 'cmd-detail-1',
      activityType: 'COMMAND',
      createdAt: '2026-10-20T17:32:12.672Z',
      description: 'rm -rf build',
      detail: { output: 'removed build/' },
      executionId: EXECUTION_ID,
      status: 'completed',
      type: 'execution_activity',
    })

    await waitFor(() => {
      expect(screen.getByText('removed build/')).toBeInTheDocument()
    })
    expect(screen.getAllByText(/removed build\//)).toHaveLength(1)
  })

  it('shows exit code for completed command execution', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        approvalRequired: false,
        collapsed: false,
        command: 'make build',
        executionId: EXECUTION_ID,
        exitCode: 0,
        id: 'cmd-exit-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'active',
        type: 'command_execution',
      },
    ])

    await waitFor(() => {
      expect(screen.getByText(/exit: 0/)).toBeInTheDocument()
    })
  })

  it('truncates a long command in the collapsed summary without widening the panel', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    const longCommand =
      'npm run build -- --config ./very/long/path/to/config/file.json --env production --verbose'
    seedActivities([
      {
        approvalRequired: false,
        collapsed: true,
        command: longCommand,
        endedAt: '2024-06-15T10:31:00.000Z',
        executionId: EXECUTION_ID,
        exitCode: 0,
        id: 'cmd-long-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        type: 'command_execution',
      },
    ])

    const summary = await screen.findByText(longCommand)
    expect(summary.className).toContain('truncate')
    expect(summary.closest('button')).toHaveAttribute('title', longCommand)
  })

  it('renders expanded output inside a horizontal-scroll container without a max-height constraint', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    const longOutput = 'a'.repeat(500)
    seedActivities([
      {
        approvalRequired: false,
        collapsed: false,
        command: 'cat file',
        detail: { output: longOutput },
        executionId: EXECUTION_ID,
        id: 'cmd-out-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'active',
        type: 'command_execution',
      },
    ])

    const line = await screen.findByText(longOutput)
    expect(line.parentElement?.className).toContain('overflow-x-auto')
    expect(line.parentElement?.className).not.toMatch(/max-h-/)
    expect(line.parentElement?.className).not.toMatch(/overflow-y-/)
  })

  it('renders an auto-approved command in the shared collapsible row', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        approvalRequired: true,
        collapsed: true,
        command: 'rm -rf /tmp/cache',
        detail: { output: 'removed cache' },
        executionId: EXECUTION_ID,
        hitlResponse: 'approved',
        id: 'cmd-auto-approved',
        resolvedBy: { kind: 'policy' },
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'active',
        type: 'command_execution',
      },
    ])

    const summary = await screen.findByText('Auto-approved: rm -rf /tmp/cache')
    expect(summary.className).toContain('truncate')
    expect(screen.queryByText('removed cache')).not.toBeInTheDocument()
    expect(document.querySelector('[data-slot="card"]')).not.toBeInTheDocument()

    await user.click(summary.closest('button')!)
    expect(await screen.findByText('removed cache')).toBeInTheDocument()

    await user.click(screen.getByText('Auto-approved: rm -rf /tmp/cache').closest('button')!)
    expect(screen.queryByText('removed cache')).not.toBeInTheDocument()
  })

  it('auto-collapses a resolved approval once it completes', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    const store = useActivityStore.getState()
    store.handleActivityEvent({
      actionId: 'cmd-approved',
      activityType: 'COMMAND',
      createdAt: '2026-10-20T17:32:12.672Z',
      description: 'git status',
      detail: {
        hitl: {
          kind: 'approval',
          message: 'Allow git status?',
          state: 'awaiting_human',
        },
      },
      executionId: EXECUTION_ID,
      status: 'pending',
      type: 'execution_activity',
    })
    store.handleActivityEvent({
      actionId: 'cmd-approved',
      activityType: 'COMMAND',
      createdAt: '2026-10-20T17:32:13.672Z',
      description: 'git status',
      detail: {
        hitl: {
          kind: 'approval',
          message: 'Allow git status?',
          response: 'approved',
          state: 'resolved',
        },
        output: 'clean',
      },
      executionId: EXECUTION_ID,
      status: 'completed',
      type: 'execution_activity',
    })

    const summary = await screen.findByText('Permission approved: git status')
    expect(summary.className).toContain('truncate')
    expect(screen.queryByText('clean')).not.toBeInTheDocument()
  })

  it('toggles warning activities under their execution', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        collapsed: false,
        executionId: EXECUTION_ID,
        id: 'warning-1',
        message: 'Invalid approval request from agent',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        type: 'warning',
      },
    ])

    expect(await screen.findByText('Invalid approval request from agent')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Warning/ }))
    expect(screen.queryByText('Invalid approval request from agent')).not.toBeInTheDocument()
  })

  it('toggles error activities under their execution', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        collapsed: false,
        executionId: EXECUTION_ID,
        id: 'error-1',
        message: 'Execution blew up',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'error',
        type: 'error',
      },
    ])

    expect(await screen.findByText('Execution blew up')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Execution failed/ }))
    expect(screen.queryByText('Execution blew up')).not.toBeInTheDocument()
  })

  it('renders a resolved elicitation in the shared collapsible row', async () => {
    renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        collapsed: true,
        executionId: EXECUTION_ID,
        id: 'elicitation-1',
        message: 'Choose a deployment target',
        response: 'answered',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        type: 'elicitation',
      },
    ])

    const summary = await screen.findByText('Question answered: Choose a deployment target')
    expect(summary.className).toContain('truncate')
    expect(screen.queryByText('Agent Question')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Submit/ })).not.toBeInTheDocument()
  })

  it('expands all collapsed activities when clicking "Expand all"', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        approvalRequired: false,
        collapsed: true,
        command: 'npm run build',
        detail: { output: 'Build complete' },
        endedAt: '2024-06-15T10:31:00.000Z',
        executionId: EXECUTION_ID,
        exitCode: 0,
        id: 'cmd-bulk-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        type: 'command_execution',
      },
      {
        collapsed: true,
        executionId: EXECUTION_ID,
        id: 'think-bulk-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        thought: 'Analyzing dependencies',
        type: 'thinking',
      },
      {
        actionId: 'msg-bulk-1',
        collapsed: true,
        detail: { messageId: 'msg-bulk-1', role: 'agent' },
        executionId: EXECUTION_ID,
        id: 'msg-bulk-1',
        role: 'agent',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        text: 'Task has been completed.',
        type: 'message',
      },
    ])

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Expand all activities/i })).toBeInTheDocument()
    })

    expect(screen.queryByText('Build complete')).not.toBeInTheDocument()
    const msgSummary = screen.getByText('Task has been completed.')
    expect(msgSummary.className).toContain('truncate')

    await user.click(screen.getByRole('button', { name: /Expand all activities/i }))

    await waitFor(() => {
      expect(screen.getByText('Build complete')).toBeInTheDocument()
    })
    const expandedMsg = screen.getByText('Task has been completed.')
    expect(expandedMsg.className).not.toContain('truncate')
    expect(expandedMsg.className).toContain('whitespace-pre-wrap')
  })

  it('collapses all expanded activities when clicking "Collapse all"', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        approvalRequired: false,
        collapsed: false,
        command: 'npm run test',
        detail: { output: 'All tests passed' },
        executionId: EXECUTION_ID,
        id: 'cmd-bulk-open',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'active',
        type: 'command_execution',
      },
      {
        actionId: 'msg-bulk-open',
        collapsed: false,
        detail: { messageId: 'msg-bulk-open', role: 'agent' },
        executionId: EXECUTION_ID,
        id: 'msg-bulk-open',
        role: 'agent',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        text: 'All work finished cleanly.',
        type: 'message',
      },
    ])

    await waitFor(() => {
      expect(screen.getByText('All tests passed')).toBeInTheDocument()
    })
    expect(screen.getByText('All work finished cleanly.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Collapse all activities/i }))

    await waitFor(() => {
      expect(screen.queryByText('All tests passed')).not.toBeInTheDocument()
    })
    const summary = screen.getByText('All work finished cleanly.')
    expect(summary.className).toContain('truncate')
  })

  it('does not collapse an expanded message when text is selected', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        actionId: 'msg-sel-1',
        collapsed: false,
        detail: { messageId: 'msg-sel-1', role: 'agent' },
        executionId: EXECUTION_ID,
        id: 'msg-sel-1',
        role: 'agent',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        text: 'Selectable agent output text that should remain visible',
        type: 'message',
      },
    ])

    const textEl = await screen.findByText('Selectable agent output text that should remain visible')
    expect(textEl.className).toContain('select-text')

    const selectionSpy = vi.spyOn(window, 'getSelection').mockReturnValue({
      toString: () => 'agent output text',
    } as Selection)

    try {
      await user.click(textEl)

      expect(screen.getByText('Selectable agent output text that should remain visible')).toBeInTheDocument()
      expect(textEl.className).not.toContain('truncate')
    } finally {
      selectionSpy.mockRestore()
    }
  })

  it('does not collapse an expanded thinking activity when text is selected', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        collapsed: false,
        executionId: EXECUTION_ID,
        id: 'think-sel-1',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        thought: 'Step-by-step thinking content to select and copy',
        type: 'thinking',
      },
    ])

    const thoughtEl = await screen.findByText('Thinking ... Step-by-step thinking content to select and copy')
    expect(thoughtEl.className).toContain('select-text')

    const selectionSpy = vi.spyOn(window, 'getSelection').mockReturnValue({
      toString: () => 'thinking content',
    } as Selection)

    try {
      const collapseButton = screen.getByRole('button', { name: 'Collapse' })
      await user.click(collapseButton)

      expect(thoughtEl.className).not.toContain('truncate')
      expect(thoughtEl.className).toContain('whitespace-pre-wrap')
    } finally {
      selectionSpy.mockRestore()
    }
  })

  it('collapses an expanded item when clicking the chevron toggle button', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        actionId: 'msg-chevron-1',
        collapsed: false,
        detail: { messageId: 'msg-chevron-1', role: 'agent' },
        executionId: EXECUTION_ID,
        id: 'msg-chevron-1',
        role: 'agent',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        text: 'Message that will be collapsed via chevron button',
        type: 'message',
      },
    ])

    await waitFor(() => {
      expect(screen.getByText('Message that will be collapsed via chevron button')).toBeInTheDocument()
    })

    const collapseButton = screen.getByRole('button', { name: 'Collapse' })
    await user.click(collapseButton)

    await waitFor(() => {
      const summary = screen.getByText('Message that will be collapsed via chevron button')
      expect(summary.className).toContain('truncate')
    })
  })

  it('expands a collapsed item when clicking its header', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        actionId: 'msg-header-1',
        collapsed: true,
        detail: { messageId: 'msg-header-1', role: 'agent' },
        executionId: EXECUTION_ID,
        id: 'msg-header-1',
        role: 'agent',
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        text: 'Detailed message line 1\nDetailed message line 2',
        type: 'message',
      },
    ])

    const collapsedHeader = await screen.findByText('Detailed message line 1 Detailed message line 2')
    expect(collapsedHeader.className).toContain('truncate')

    await user.click(collapsedHeader.closest('button')!)

    await waitFor(() => {
      const expandedText = screen.getByText('Detailed message line 1 Detailed message line 2')
      expect(expandedText.className).not.toContain('truncate')
      expect(expandedText.className).toContain('whitespace-pre-wrap')
    })
  })
})

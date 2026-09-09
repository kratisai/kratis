import { beforeEach, describe, expect, it } from 'vitest'

import type { CommandExecutionActivity, ThinkingActivity, ToolExecutionActivity } from '@/types/execution-activity-types'

import { ExecutionActivityLog } from '@/components/session/execution-activity-log'
import { useActivityStore } from '@/store/activity-store'

import { setupFetchMock } from '../support/test-fetch-mocks'
import { renderWithProviders, screen, waitFor } from '../support/test-render'

const EXECUTION_ID = 'exec-expanded-1'

function seedActivities(activities: Array<CommandExecutionActivity | ThinkingActivity | ToolExecutionActivity>) {
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
        endedAt: '2024-06-15T10:31:00.000Z',
        executionId: EXECUTION_ID,
        exitCode: 0,
        id: 'cmd-1',
        output: ['Building project...', 'Build complete'],
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'completed',
        type: 'command_execution',
      },
    ])

    await waitFor(() => {
      expect(screen.getByText('npm run build')).toBeInTheDocument()
    })
    expect(screen.queryByText('Building project...')).not.toBeInTheDocument()

    const expandButton = screen.getByText('npm run build').closest('button')!
    await user.click(expandButton)

    await waitFor(() => {
      expect(screen.getByText('Building project...')).toBeInTheDocument()
    })
    expect(screen.getByText('Build complete')).toBeInTheDocument()
  })

  it('renders error command execution auto-collapsed with X icon', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        approvalRequired: false,
        collapsed: true,
        command: 'rm -rf /tmp/cache',
        endedAt: '2024-06-15T10:31:00.000Z',
        executionId: EXECUTION_ID,
        exitCode: 1,
        id: 'cmd-err-1',
        output: ['Permission denied'],
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
        executionId: EXECUTION_ID,
        id: 'cmd-active-1',
        output: ['Test suite running...', 'PASS src/app.test.ts'],
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'active',
        type: 'command_execution',
      },
    ])

    await waitFor(() => {
      expect(screen.getByText('npm test')).toBeInTheDocument()
    })

    expect(screen.getByText('Test suite running...')).toBeInTheDocument()
    expect(screen.getByText('PASS src/app.test.ts')).toBeInTheDocument()
  })

  it('renders completed thinking activity auto-collapsed with a single-line summary title', async () => {
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

    await waitFor(() => {
      expect(screen.getByText('I need to analyze the project structure first.')).toBeInTheDocument()
    })
    expect(screen.queryByText('Thinking')).not.toBeInTheDocument()

    const expandButton = screen.getByText('I need to analyze the project structure first.').closest('button')!
    await user.click(expandButton)

    await waitFor(() => {
      expect(screen.getAllByText('I need to analyze the project structure first.')).toHaveLength(2)
    })
  })

  it('renders active thinking activity expanded with spinner', async () => {
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
      expect(screen.getAllByText('Processing the request...').length).toBeGreaterThanOrEqual(1)
    })
  })

  it('uses the first line of a multi-line thought as the collapsed summary title', async () => {
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

    await waitFor(() => {
      expect(screen.getByText('Searching for the failing test.')).toBeInTheDocument()
    })
    expect(screen.queryByText(/Found it in src\/app\.test\.ts/)).not.toBeInTheDocument()
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

  it('keeps active commands expanded (not closable) and toggles completed ones manually', async () => {
    const { user } = renderWithProviders(<ExecutionActivityLog executionId={EXECUTION_ID} />)

    seedActivities([
      {
        approvalRequired: false,
        collapsed: false,
        command: 'echo hello',
        executionId: EXECUTION_ID,
        id: 'cmd-toggle-1',
        output: ['hello'],
        startedAt: '2024-06-15T10:30:00.000Z',
        state: 'active',
        type: 'command_execution',
      },
      {
        approvalRequired: false,
        collapsed: false,
        command: 'echo done',
        endedAt: '2024-06-15T10:31:00.000Z',
        executionId: EXECUTION_ID,
        exitCode: 0,
        id: 'cmd-toggle-2',
        output: ['done'],
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
    expect(screen.getByText('hello')).toBeInTheDocument()

    const doneHeader = screen.getByText('echo done').closest('button')!
    await user.click(doneHeader)
    expect(screen.queryByText('done')).not.toBeInTheDocument()

    await user.click(screen.getByText('echo done').closest('button')!)
    expect(screen.getByText('done')).toBeInTheDocument()
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
        output: [],
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
        output: [],
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
        executionId: EXECUTION_ID,
        id: 'cmd-out-1',
        output: [longOutput],
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
})

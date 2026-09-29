import { afterEach, describe, expect, it } from 'vitest'

import type {
  CommandExecutionActivity,
  ThinkingActivity,
  ToolExecutionActivity,
} from '@/types/execution-activity-types'

import { downloadActivityLog, formatActivityLog } from '@/lib/activity-log-export'

import { mockDownloads, restoreDownloads } from '../support/test-download'

const EXECUTION_ID = 'exec-1'

const commandActivity: CommandExecutionActivity = {
  approvalRequired: false,
  collapsed: false,
  command: 'npm run build',
  detail: { output: 'Building project...\nBuild complete' },
  endedAt: '2024-06-15T10:31:00.000Z',
  executionId: EXECUTION_ID,
  exitCode: 0,
  id: 'cmd-1',
  startedAt: '2024-06-15T10:30:00.000Z',
  state: 'completed',
  type: 'command_execution',
}

const thinkingActivity: ThinkingActivity = {
  collapsed: false,
  executionId: EXECUTION_ID,
  id: 'think-1',
  startedAt: '2024-06-15T10:30:00.000Z',
  state: 'completed',
  thought: 'Analyzing the project structure first.',
  type: 'thinking',
}

const toolActivity: ToolExecutionActivity = {
  collapsed: false,
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
}

describe('formatActivityLog', () => {
  it('includes the execution id header', () => {
    const text = formatActivityLog([], EXECUTION_ID)

    expect(text).toContain('Execution Activity Log')
    expect(text).toContain(`Execution ID: ${EXECUTION_ID}`)
    expect(text).toContain('Downloaded: ')
  })

  it('formats a command execution with output and exit code', () => {
    const text = formatActivityLog([commandActivity], EXECUTION_ID)

    expect(text).toContain('[Command Execution] npm run build')
    expect(text).toContain('State: completed')
    expect(text).toContain('Started: 2024-06-15T10:30:00.000Z')
    expect(text).toContain('Ended: 2024-06-15T10:31:00.000Z')
    expect(text).toContain('Exit code: 0')
    expect(text).toContain('Output:')
    expect(text).toContain('  Building project...')
    expect(text).toContain('  Build complete')
  })

  it('formats the approval outcome of a command execution', () => {
    const text = formatActivityLog(
      [
        {
          ...commandActivity,
          approvalRequired: true,
          approved: true,
          state: 'active',
        },
      ],
      EXECUTION_ID,
    )

    expect(text).toContain('Approval: approved')
  })

  it('formats a thinking activity', () => {
    const text = formatActivityLog([thinkingActivity], EXECUTION_ID)

    expect(text).toContain('[Thinking]')
    expect(text).toContain('State: completed')
    expect(text).toContain('Thought: Analyzing the project structure first.')
  })

  it('formats a tool execution with result', () => {
    const text = formatActivityLog([toolActivity], EXECUTION_ID)

    expect(text).toContain('[Tool Execution] file_search')
    expect(text).toContain('Thought: Searching for TypeScript files')
    expect(text).toContain('Result: Found 42 files')
  })

  it('formats a tool execution error', () => {
    const text = formatActivityLog(
      [{ ...toolActivity, error: 'File not found', result: undefined }],
      EXECUTION_ID,
    )

    expect(text).toContain('Error: File not found')
    expect(text).not.toContain('Result:')
  })

  it('separates multiple activities with blank lines', () => {
    const text = formatActivityLog([commandActivity, thinkingActivity], EXECUTION_ID)

    expect(text).toContain('  Build complete\n\n[Thinking]')
  })
})

describe('downloadActivityLog', () => {
  afterEach(() => {
    restoreDownloads()
  })

  it('downloads the formatted log as a text file named after the execution', async () => {
    const { clickedAnchors, createObjectURL, revokeObjectURL } = mockDownloads()

    downloadActivityLog([commandActivity, toolActivity], EXECUTION_ID)

    expect(createObjectURL).toHaveBeenCalledTimes(1)
    const blob = createObjectURL.mock.calls[0][0]
    expect(blob.type).toBe('text/plain;charset=utf-8')
    expect(await blob.text()).toContain('[Command Execution] npm run build')
    expect(await blob.text()).toContain('[Tool Execution] file_search')

    expect(clickedAnchors).toHaveLength(1)
    const anchor = clickedAnchors[0]
    expect(anchor.download).toBe('execution-activity-log-exec-1.txt')
    expect(anchor.href).toBe('blob:mock-download')
    expect(anchor.isConnected).toBe(false)

    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock-download')
  })
})

import type {
  Activity,
  CommandExecutionActivity,
  ElicitationActivity,
  ErrorActivity,
  MessageActivity,
  PlanActivity,
  ThinkingActivity,
  ToolExecutionActivity,
} from '@/types/execution-activity-types'

export function downloadActivityLog(activities: Activity[], executionId: string): void {
  const blob = new Blob([formatActivityLog(activities, executionId)], {
    type: 'text/plain;charset=utf-8',
  })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `execution-activity-log-${executionId}.txt`
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  URL.revokeObjectURL(url)
}

export function formatActivityLog(activities: Activity[], executionId: string): string {
  const lines = [
    'Execution Activity Log',
    `Execution ID: ${executionId}`,
    `Downloaded: ${new Date().toISOString()}`,
    '',
  ]
  for (const activity of activities) {
    lines.push(...formatActivity(activity), '')
  }
  return lines.join('\n')
}

function formatActivity(activity: Activity): string[] {
  switch (activity.type) {
    case 'command_execution':
      return formatCommandExecution(activity)
    case 'elicitation':
      return formatElicitation(activity)
    case 'error':
      return formatError(activity)
    case 'message':
      return formatMessage(activity)
    case 'plan':
      return formatPlan(activity)
    case 'thinking':
      return formatThinking(activity)
    case 'tool_execution':
      return formatToolExecution(activity)
  }
}

function formatCommandExecution(activity: CommandExecutionActivity): string[] {
  const lines = [`[Command Execution] ${activity.command}`]
  formatCommon(lines, activity)
  if (activity.exitCode !== undefined) {
    lines.push(`Exit code: ${activity.exitCode}`)
  }
  if (activity.approvalRequired) {
    lines.push(
      `Approval: ${activity.approved === undefined ? 'pending' : activity.approved ? 'approved' : 'rejected'}`,
    )
  }
  if (activity.detail?.output) {
    lines.push('Output:', indent(activity.detail.output))
  }
  return lines
}

function formatCommon(
  lines: string[],
  activity: { endedAt?: string; startedAt: string; state: string },
): string[] {
  lines.push(`State: ${activity.state}`)
  lines.push(`Started: ${activity.startedAt}`)
  if (activity.endedAt) {
    lines.push(`Ended: ${activity.endedAt}`)
  }
  return lines
}

function formatElicitation(activity: ElicitationActivity): string[] {
  const lines = [`[Elicitation] ${activity.message}`]
  formatCommon(lines, activity)
  if (activity.response) {
    lines.push(`Response: ${activity.response}`)
  }
  return lines
}

function formatError(activity: ErrorActivity): string[] {
  const lines = [`[Error] ${activity.message}`]
  formatCommon(lines, activity)
  if (activity.exitCode !== undefined) {
    lines.push(`Exit code: ${activity.exitCode}`)
  }
  return lines
}

function formatMessage(activity: MessageActivity): string[] {
  const lines = [`[Message: ${activity.role}] ${activity.text}`]
  formatCommon(lines, activity)
  return lines
}

function formatPlan(activity: PlanActivity): string[] {
  const lines = ['[Plan]']
  formatCommon(lines, activity)
  for (const entry of activity.plan) {
    lines.push(`  [${entry.status}] (${entry.priority}) ${entry.content}`)
  }
  return lines
}

function formatThinking(activity: ThinkingActivity): string[] {
  const lines = ['[Thinking]']
  formatCommon(lines, activity)
  lines.push(`Thought: ${activity.thought}`)
  return lines
}

function formatToolExecution(activity: ToolExecutionActivity): string[] {
  const lines = [`[Tool Execution] ${activity.toolName}`]
  formatCommon(lines, activity)
  if (activity.thought) {
    lines.push(`Thought: ${activity.thought}`)
  }
  if (activity.result) {
    lines.push(`Result: ${activity.result}`)
  }
  if (activity.error) {
    lines.push(`Error: ${activity.error}`)
  }
  return lines
}

function indent(line: string): string {
  return line
    .split('\n')
    .map((part) => `  ${part}`)
    .join('\n')
}

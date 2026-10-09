import { create } from 'zustand'

import type {
  Activity,
  ActivityDetail,
  ActivityHitl,
  ActivityState,
  CommandExecutionActivity,
  ElicitationActivity,
  ErrorActivity,
  MessageActivity,
  PlanActivity,
  ThinkingActivity,
  ToolExecutionActivity,
} from '@/types/execution-activity-types'
import type { CreateHitlRuleRequest } from '@/types/hitl-rule-types'
import type {
  ExecutionActivityResult,
  ExecutionCompleteResult,
  HitlResponse,
  WireActivityStatus,
} from '@/types/websocket-types'

import { useAuthStore } from '@/store/auth-store'

let activityIdCounter = 0

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

interface ExecutionActivityState {
  activitiesByExecution: Record<string, Activity[]>
  cancelHitl: (executionId: string, actionId: string) => Promise<void>
  clearActivities: (executionId: string) => void
  handleActivityEvent: (result: ExecutionActivityResult) => void
  handleExecutionComplete: (result: ExecutionCompleteResult) => void
  resolveHitl: (
    executionId: string,
    actionId: string,
    response: HitlResponse,
    optionId?: string,
    content?: Record<string, unknown>,
    rules?: CreateHitlRuleRequest[],
    feedback?: string,
  ) => Promise<void>
  toggleCollapsed: (executionId: string, activityId: string) => void
}

type ToolRecord = CommandExecutionActivity | ToolExecutionActivity

export function activityCommand(activity: Activity): string {
  if (activity.type === 'command_execution') {
    return activity.command
  }
  const detail = activity.detail
  const rawCommand = detail?.input?.['command']
  if (typeof rawCommand === 'string' && rawCommand !== '') {
    return rawCommand
  }
  if (typeof detail?.title === 'string' && detail.title !== '') {
    return detail.title
  }
  if (activity.type === 'tool_execution') {
    return activity.toolName
  }
  return ''
}

export function activityTitle(activity: Activity): string {
  switch (activity.type) {
    case 'command_execution':
      return activity.command
    case 'elicitation':
      return activity.message
    case 'error':
      return 'Execution failed'
    case 'message':
      return activity.role === 'user' ? 'User' : 'Agent'
    case 'plan':
      return 'Plan'
    case 'thinking':
      return activity.thought.split('\n')[0].trim() || 'Thinking'
    case 'tool_execution': {
      const detail = activity.detail
      const kind = detail?.kind
      const target = toolTarget(detail)
      if (kind && target) {
        return `${kind} ${target}`
      }
      return detail?.title ?? activity.toolName
    }
  }
}

function activityStateFrom(
  status: undefined | WireActivityStatus,
  detail?: ActivityDetail,
): ActivityState {
  // Only the control plane's awaiting_human state means a person must act.
  const hitl = detail?.hitl
  if (hitl?.kind === 'approval' && hitl.state === 'awaiting_human') return 'pending_approval'
  if (status === 'completed') {
    return 'completed'
  }
  if (status === 'failed') {
    return 'error'
  }
  return 'active'
}

function appendActivity(existing: Activity[], activity: Activity): Activity[] {
  const next = [...existing]
  const last = next.at(-1)
  if (
    last &&
    isTerminalState(last.state) &&
    !last.collapsed &&
    last.type !== 'message' &&
    last.type !== 'thinking' &&
    last.type !== 'plan'
  ) {
    next[next.length - 1] = { ...last, collapsed: true }
  }
  next.push(activity)
  return next
}

/** Approval fields projected from the control plane's `detail.hitl`; absent values keep the record's own. */
function approvalFields(hitl: ActivityHitl | undefined) {
  if (!hitl || hitl.kind !== 'approval') {
    return {}
  }
  const fields = {
    approvalRequired: hitl.state !== undefined,
    approved: hitl.response === undefined ? undefined : hitl.response === 'approved',
    hitlResponse: hitl.response,
    permissionDiff: hitl.diff,
    permissionKind: hitl.toolKind,
    permissionOptions: hitl.options,
    permissionSegments: hitl.commandSegments,
    permissionTitle: hitl.title,
    resolvedBy: hitl.resolvedBy,
  }
  return Object.fromEntries(Object.entries(fields).filter(([, value]) => value !== undefined))
}

function collapsedOnTransition(
  current: { collapsed: boolean; state: ActivityState },
  next: ActivityState,
): boolean {
  if (!isTerminalState(current.state) && isTerminalState(next)) {
    return true
  }
  return current.collapsed
}

function commandRecordFrom(current: ToolRecord, command: string): CommandExecutionActivity {
  if (current.type === 'command_execution') return current
  return {
    actionId: current.actionId,
    approvalRequired: current.approvalRequired ?? false,
    approved: current.approved,
    collapsed: current.collapsed,
    command,
    detail: current.detail,
    endedAt: current.endedAt,
    executionId: current.executionId,
    exitCode: current.detail?.exitCode,
    hitlResponse: current.hitlResponse,
    id: current.id,
    permissionDiff: current.permissionDiff,
    permissionKind: current.permissionKind,
    permissionOptions: current.permissionOptions,
    permissionSegments: current.permissionSegments,
    permissionTitle: current.permissionTitle,
    resolvedBy: current.resolvedBy,
    startedAt: current.startedAt,
    state: current.state,
    type: 'command_execution',
  }
}

async function fetchWithAuth(url: string, options: RequestInit = {}): Promise<Response> {
  const { accessToken } = useAuthStore.getState()

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string>),
  }

  if (accessToken) {
    headers['Authorization'] = `Bearer ${accessToken}`
  }

  return window.fetch(`${API_BASE_URL}${url}`, { ...options, headers })
}

// One actionId is one record; a late tool kind moves the record between the
// command and tool buckets without duplicating it or changing its position.
function findToolRecordIndex(activities: Activity[], actionId: string | undefined): number {
  if (!actionId) return -1
  return activities.findIndex(
    (a) =>
      (a.type === 'command_execution' || a.type === 'tool_execution') && a.actionId === actionId,
  )
}

function isTerminalState(state: ActivityState): boolean {
  return state === 'completed' || state === 'error'
}

function nextActivityId(): string {
  activityIdCounter += 1
  return `activity-${activityIdCounter}`
}

function nextActivityState(
  current: { state: ActivityState },
  status: undefined | WireActivityStatus,
  detail?: ActivityDetail,
): ActivityState {
  const next = activityStateFrom(status, detail)
  // Sidecar lifecycle frames carry no approval state, so they must not clear a pending prompt.
  if (current.state === 'pending_approval' && next === 'active' && detail?.hitl === undefined) {
    return 'pending_approval'
  }
  return next
}

function toolRecordFrom(current: ToolRecord): ToolExecutionActivity {
  if (current.type === 'tool_execution') return current
  return {
    actionId: current.actionId,
    approvalRequired: current.approvalRequired,
    approved: current.approved,
    collapsed: current.collapsed,
    detail: current.detail,
    endedAt: current.endedAt,
    executionId: current.executionId,
    hitlResponse: current.hitlResponse,
    id: current.id,
    permissionDiff: current.permissionDiff,
    permissionKind: current.permissionKind,
    permissionOptions: current.permissionOptions,
    permissionSegments: current.permissionSegments,
    permissionTitle: current.permissionTitle,
    resolvedBy: current.resolvedBy,
    startedAt: current.startedAt,
    state: current.state,
    taskId: '',
    thought: '',
    toolName: current.command,
    type: 'tool_execution',
  }
}

function toolTarget(detail: ActivityDetail | undefined): string | undefined {
  if (!detail) return undefined
  const input = detail.input
  const candidates = [
    input?.['filePath'],
    input?.['file_path'],
    input?.['path'],
    input?.['pattern'],
    input?.['command'],
    input?.['url'],
    detail.locations?.[0]?.path,
  ]
  for (const candidate of candidates) {
    if (typeof candidate === 'string' && candidate !== '') return candidate
  }
  return undefined
}

export const useActivityStore = create<ExecutionActivityState>((set) => ({
  activitiesByExecution: {},
  cancelHitl: async (executionId: string, actionId: string) => {
    const response = await fetchWithAuth('/api/v1/hitl/resolve', {
      body: JSON.stringify({
        actionId,
        content: null,
        executionId,
        optionId: null,
        response: 'cancelled',
      }),
      method: 'POST',
    })
    if (!response.ok) {
      throw new Error(`Failed to cancel HITL: ${response.status}`)
    }
  },
  clearActivities: (executionId: string) => {
    set((state) => {
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      const { [executionId]: _removed, ...rest } = state.activitiesByExecution
      return { activitiesByExecution: rest }
    })
  },

  handleActivityEvent: (result: ExecutionActivityResult) => {
    const executionId = result.executionId
    const { actionId, activityType, description, detail, status } = result

    if (activityType === 'PLAN') {
      set((state) => {
        const existing = state.activitiesByExecution[executionId] ?? []
        const plan = detail?.plan ?? []
        const planActionId = actionId ?? `plan-${nextActivityId()}`
        const idx = existing.findIndex((a) => a.type === 'plan' && a.actionId === planActionId)
        if (idx >= 0) {
          const updated = [...existing]
          const current = updated[idx] as PlanActivity
          updated[idx] = {
            ...current,
            detail,
            plan,
            state: activityStateFrom(status, detail),
          }
          return {
            activitiesByExecution: {
              ...state.activitiesByExecution,
              [executionId]: updated,
            },
          }
        }
        const activity: PlanActivity = {
          actionId: planActionId,
          collapsed: false,
          detail,
          executionId,
          id: nextActivityId(),
          plan,
          startedAt: new Date().toISOString(),
          state: activityStateFrom(status, detail),
          type: 'plan',
        }
        return {
          activitiesByExecution: {
            ...state.activitiesByExecution,
            [executionId]: appendActivity(existing, activity),
          },
        }
      })
      return
    }

    if (activityType === 'MESSAGE') {
      set((state) => {
        const existing = state.activitiesByExecution[executionId] ?? []
        const messageId = actionId ?? detail?.messageId
        const idx = messageId
          ? existing.findIndex((a) => a.type === 'message' && a.actionId === messageId)
          : -1
        if (idx >= 0) {
          const updated = [...existing]
          const current = updated[idx] as MessageActivity
          const nextState = activityStateFrom(status, detail)
          updated[idx] = {
            ...current,
            endedAt:
              nextState === 'completed' ? (current.endedAt ?? new Date().toISOString()) : undefined,
            state: nextState,
            text: description,
          }
          return {
            activitiesByExecution: {
              ...state.activitiesByExecution,
              [executionId]: updated,
            },
          }
        }
        const activity: MessageActivity = {
          actionId: messageId,
          collapsed: false,
          detail,
          executionId,
          id: nextActivityId(),
          role: detail?.role === 'user' ? 'user' : 'agent',
          startedAt: new Date().toISOString(),
          state: activityStateFrom(status, detail),
          text: description,
          type: 'message',
        }
        return {
          activitiesByExecution: {
            ...state.activitiesByExecution,
            [executionId]: appendActivity(existing, activity),
          },
        }
      })
    } else if (activityType === 'THINKING') {
      set((state) => {
        const existing = state.activitiesByExecution[executionId] ?? []
        const messageId = actionId ?? detail?.messageId
        const idx = messageId
          ? existing.findIndex((a) => a.type === 'thinking' && a.actionId === messageId)
          : -1
        if (idx >= 0) {
          const updated = [...existing]
          const current = updated[idx] as ThinkingActivity
          const nextState = activityStateFrom(status, detail)
          updated[idx] = {
            ...current,
            collapsed: collapsedOnTransition(current, nextState),
            detail,
            endedAt:
              nextState === 'completed' ? (current.endedAt ?? new Date().toISOString()) : undefined,
            state: nextState,
            thought: description,
          }
          return {
            activitiesByExecution: {
              ...state.activitiesByExecution,
              [executionId]: updated,
            },
          }
        }
        const activity: ThinkingActivity = {
          actionId: messageId,
          collapsed: false,
          detail,
          executionId,
          id: nextActivityId(),
          startedAt: new Date().toISOString(),
          state: activityStateFrom(status, detail),
          thought: description,
          type: 'thinking',
        }
        return {
          activitiesByExecution: {
            ...state.activitiesByExecution,
            [executionId]: appendActivity(existing, activity),
          },
        }
      })
    } else if (activityType === 'RESEARCH' || activityType === 'EDITED') {
      set((state) => {
        const existing = state.activitiesByExecution[executionId] ?? []
        const idx = findToolRecordIndex(existing, actionId)
        const current = idx >= 0 ? existing[idx] : undefined
        if (
          current &&
          (current.type === 'tool_execution' || current.type === 'command_execution')
        ) {
          const nextState = nextActivityState(current, status, detail)
          const updated = [...existing]
          updated[idx] = {
            ...toolRecordFrom(current),
            ...approvalFields(detail?.hitl),
            collapsed: collapsedOnTransition(current, nextState),
            detail,
            state: nextState,
            toolName: detail?.title ?? description,
          }
          return {
            activitiesByExecution: {
              ...state.activitiesByExecution,
              [executionId]: updated,
            },
          }
        }
        const activity: ToolExecutionActivity = {
          actionId,
          ...approvalFields(detail?.hitl),
          collapsed: false,
          detail,
          executionId,
          id: nextActivityId(),
          startedAt: new Date().toISOString(),
          state: activityStateFrom(status, detail),
          taskId: '',
          thought: '',
          toolName: detail?.title ?? description,
          type: 'tool_execution',
        }
        return {
          activitiesByExecution: {
            ...state.activitiesByExecution,
            [executionId]: appendActivity(existing, activity),
          },
        }
      })
    } else if (activityType === 'COMMAND') {
      set((state) => {
        const existing = state.activitiesByExecution[executionId] ?? []
        const command = getDetailCommand(detail) ?? description
        const idx = findToolRecordIndex(existing, actionId)
        const current = idx >= 0 ? existing[idx] : undefined
        if (
          current &&
          (current.type === 'tool_execution' || current.type === 'command_execution')
        ) {
          const nextState = nextActivityState(current, status, detail)
          const updated = [...existing]
          updated[idx] = {
            ...commandRecordFrom(current, command),
            ...approvalFields(detail?.hitl),
            collapsed: collapsedOnTransition(current, nextState),
            command,
            detail,
            exitCode: detail?.exitCode ?? current.detail?.exitCode,
            state: nextState,
          }
          return {
            activitiesByExecution: {
              ...state.activitiesByExecution,
              [executionId]: updated,
            },
          }
        }
        const activity: CommandExecutionActivity = {
          actionId,
          approvalRequired: false,
          ...approvalFields(detail?.hitl),
          collapsed: false,
          command,
          detail,
          executionId,
          exitCode: detail?.exitCode,
          id: nextActivityId(),
          startedAt: new Date().toISOString(),
          state: activityStateFrom(status, detail),
          type: 'command_execution',
        }
        return {
          activitiesByExecution: {
            ...state.activitiesByExecution,
            [executionId]: appendActivity(existing, activity),
          },
        }
      })
    } else if (activityType === 'ERROR') {
      set((state) => {
        const existing = state.activitiesByExecution[executionId] ?? []
        const idx = existing.findIndex((a) => a.type === 'error')
        const now = new Date().toISOString()
        const activity: ErrorActivity = {
          actionId,
          collapsed: false,
          detail,
          endedAt: now,
          executionId,
          exitCode: detail?.exitCode,
          id: idx >= 0 ? existing[idx].id : nextActivityId(),
          message: description,
          startedAt: idx >= 0 ? existing[idx].startedAt : now,
          state: 'error',
          type: 'error',
        }
        const updated =
          idx >= 0
            ? existing.map((a, i) => (i === idx ? activity : a))
            : appendActivity(existing, activity)
        return {
          activitiesByExecution: {
            ...state.activitiesByExecution,
            [executionId]: updated,
          },
        }
      })
    } else {
      set((state) => {
        const existing = state.activitiesByExecution[executionId] ?? []
        const hitl = detail?.hitl
        const idx = actionId
          ? existing.findIndex((a) => a.type === 'elicitation' && a.actionId === actionId)
          : -1
        if (idx >= 0) {
          const updated = [...existing]
          const current = updated[idx] as ElicitationActivity
          updated[idx] = {
            ...current,
            collapsed: collapsedOnTransition(current, activityStateFrom(status, detail)),
            content: hitl?.content ?? current.content,
            detail,
            form: hitl?.form ?? current.form,
            response: hitl?.response ?? current.response,
            state: activityStateFrom(status, detail),
          }
          return {
            activitiesByExecution: {
              ...state.activitiesByExecution,
              [executionId]: updated,
            },
          }
        }
        const activity: ElicitationActivity = {
          actionId,
          collapsed: false,
          content: hitl?.content,
          detail,
          executionId,
          form: hitl?.form,
          id: nextActivityId(),
          message: hitl?.message ?? description,
          response: hitl?.response,
          startedAt: new Date().toISOString(),
          state: activityStateFrom(status, detail),
          type: 'elicitation',
        }
        return {
          activitiesByExecution: {
            ...state.activitiesByExecution,
            [executionId]: appendActivity(existing, activity),
          },
        }
      })
    }
  },

  handleExecutionComplete: (result: ExecutionCompleteResult) => {
    set((state) => {
      const activities = state.activitiesByExecution[result.executionId] ?? []
      if (activities.length === 0) {
        return state
      }

      // The execution is over, so every still-active activity (tool, command,
      // message, thinking) is done whether or not the agent reported a terminal
      // status. Pending HITL approvals are left for the resolution event.
      const updated = [...activities]
      for (let i = 0; i < updated.length; i++) {
        const activity = updated[i]
        if (activity.state !== 'active') continue
        const nextState: ActivityState =
          activity.type === 'command_execution' && result.status === 'FAILED'
            ? 'error'
            : 'completed'
        const collapsed =
          activity.type === 'message' || activity.type === 'plan'
            ? activity.collapsed
            : collapsedOnTransition(activity, nextState)
        const endedAt = new Date().toISOString()
        updated[i] =
          activity.type === 'command_execution'
            ? { ...activity, collapsed, endedAt, exitCode: result.exitCode, state: nextState }
            : { ...activity, collapsed, endedAt, state: nextState }
      }

      return {
        activitiesByExecution: {
          ...state.activitiesByExecution,
          [result.executionId]: updated,
        },
      }
    })
  },

  resolveHitl: async (
    executionId: string,
    actionId: string,
    response: HitlResponse,
    optionId?: string,
    content?: Record<string, unknown>,
    rules?: CreateHitlRuleRequest[],
    feedback?: string,
  ) => {
    const body: Record<string, unknown> = { actionId, executionId, response }
    if (optionId !== undefined) body.optionId = optionId
    if (content !== undefined) body.content = content
    if (rules !== undefined && rules.length > 0) body.rules = rules
    if (feedback !== undefined && feedback.trim() !== '') body.feedback = feedback.trim()
    const httpResponse = await fetchWithAuth('/api/v1/hitl/resolve', {
      body: JSON.stringify(body),
      method: 'POST',
    })
    if (!httpResponse.ok) {
      throw new Error(`Failed to resolve HITL: ${httpResponse.status}`)
    }
  },

  toggleCollapsed: (executionId: string, activityId: string) => {
    set((state) => {
      const existing = state.activitiesByExecution[executionId] ?? []
      if (existing.length === 0) return state
      const updated = existing.map((activity) =>
        activity.id === activityId ? { ...activity, collapsed: !activity.collapsed } : activity,
      )
      return {
        activitiesByExecution: {
          ...state.activitiesByExecution,
          [executionId]: updated,
        },
      }
    })
  },
}))

function getDetailCommand(detail: ActivityDetail | undefined): string | undefined {
  const rawCommand = detail?.input?.['command']
  if (typeof rawCommand === 'string' && rawCommand !== '') {
    return rawCommand
  }
  return undefined
}

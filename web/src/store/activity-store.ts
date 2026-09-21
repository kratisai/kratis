import { create } from 'zustand'

import type {
  Activity,
  ActivityDetail,
  ActivityHitl,
  ActivityState,
  CommandExecutionActivity,
  ElicitationActivity,
  MessageActivity,
  PlanActivity,
  ThinkingActivity,
  ToolExecutionActivity,
} from '@/types/execution-activity-types'
import type { CreateHitlRuleRequest } from '@/types/hitl-rule-types'
import type {
  ExecutionActivityResult,
  ExecutionCompleteResult,
  ExecutionHitlRequiredResult,
  ExecutionHitlResolvedResult,
  ExecutionOutputResult,
  HitlResponse,
  WireActivityStatus,
} from '@/types/websocket-types'

import { useAuthStore } from '@/store/auth-store'

let activityIdCounter = 0

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

interface ExecutionActivityState {
  activitiesByExecution: Record<string, Activity[]>
  cancelHitl: (executionId: string, hitlId: string) => Promise<void>
  clearActivities: (executionId: string) => void
  getPendingApproval: (executionId: string) => CommandExecutionActivity | null
  handleActivityEvent: (result: ExecutionActivityResult) => void
  handleExecutionComplete: (result: ExecutionCompleteResult) => void
  handleExecutionOutput: (result: ExecutionOutputResult) => void
  handleHitlRequired: (result: ExecutionHitlRequiredResult) => void
  handleHitlResolved: (result: ExecutionHitlResolvedResult) => void
  resolveHitl: (
    executionId: string,
    hitlId: string,
    response: HitlResponse,
    optionId?: string,
    content?: Record<string, unknown>,
    rules?: CreateHitlRuleRequest[],
    feedback?: string,
  ) => Promise<void>
  toggleCollapsed: (executionId: string, activityId: string) => void
}

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
  const hitl = detail?.hitl
  if (status === 'pending' && hitl && !hitl.response) {
    if (hitl.kind === 'approval') return 'pending_approval'
    return 'active'
  }
  if (status === 'pending' && hitl?.response) {
    return hitl.response === 'approved' || hitl.response === 'answered' ? 'completed' : 'error'
  }
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

// Agents do not reliably send a terminal status for every tool call
function closeOpenActivities(
  activities: Activity[],
  exceptActionId: string | undefined,
): Activity[] {
  let changed = false
  const updated = [...activities]
  for (let i = 0; i < updated.length; i++) {
    const activity = updated[i]
    if (activity.state !== 'active') continue
    if (activity.type === 'elicitation') continue
    if (activity.type === 'plan') {
      // Plan snapshots are superseded by the next activity but stay expanded
      // unless the user closed them — closing must not collapse them out of
      // view.
      changed = true
      updated[i] = {
        ...activity,
        endedAt: new Date().toISOString(),
        state: 'completed',
      }
      continue
    }
    if (exceptActionId !== undefined && activity.actionId === exceptActionId) continue
    changed = true
    updated[i] = {
      ...activity,
      collapsed: activity.type === 'message' ? activity.collapsed : true,
      endedAt: new Date().toISOString(),
      state: 'completed',
    }
  }
  return changed ? updated : activities
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
  if (
    current.state === 'pending_approval' &&
    next === 'active' &&
    detail?.hitl?.response === undefined
  ) {
    return 'pending_approval'
  }
  return next
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

function withHitlApproval<T extends { approvalRequired: boolean; approved?: boolean }>(
  activity: T,
  hitl: ActivityHitl | undefined,
): T {
  if (!hitl || hitl.kind !== 'approval') {
    return activity
  }
  const approved = hitl.response === 'approved'
  const hasResponse = hitl.response !== undefined
  return {
    ...activity,
    approvalRequired: hasResponse || activity.approvalRequired,
    approved: hasResponse ? approved : activity.approved,
  }
}

export const useActivityStore = create<ExecutionActivityState>((set, get) => ({
  activitiesByExecution: {},
  cancelHitl: async (executionId: string, hitlId: string) => {
    const response = await fetchWithAuth('/api/v1/hitl/resolve', {
      body: JSON.stringify({
        content: null,
        executionId,
        hitlId,
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

  getPendingApproval: (executionId: string) => {
    const activities = get().activitiesByExecution[executionId] ?? []
    if (activities.length === 0) return null
    const last = activities[activities.length - 1]
    if (last.type === 'command_execution' && last.state === 'pending_approval') {
      return last
    }
    return null
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
        const withClosed = closeOpenActivities(existing, planActionId)
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
            [executionId]: appendActivity(withClosed, activity),
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
          const nextText = description.startsWith(current.text)
            ? description
            : current.text + description
          const nextState = activityStateFrom(status, detail)
          updated[idx] = {
            ...current,
            endedAt:
              nextState === 'completed' ? (current.endedAt ?? new Date().toISOString()) : undefined,
            state: nextState,
            text: nextText,
          }
          return {
            activitiesByExecution: {
              ...state.activitiesByExecution,
              [executionId]: updated,
            },
          }
        }
        const withClosed = closeOpenActivities(existing, messageId)
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
            [executionId]: appendActivity(withClosed, activity),
          },
        }
      })
    } else if (activityType === 'THINKING') {
      set((state) => {
        const existing = state.activitiesByExecution[executionId] ?? []
        const messageId = actionId ?? detail?.messageId
        let idx = messageId
          ? existing.findIndex((a) => a.type === 'thinking' && a.actionId === messageId)
          : -1
        if (idx < 0 && messageId === undefined) {
          const lastIdx = existing.length - 1
          const last = existing.at(-1)
          if (last && last.type === 'thinking' && last.state === 'active') {
            idx = lastIdx
          }
        }
        if (idx >= 0) {
          const updated = [...existing]
          const current = updated[idx] as ThinkingActivity
          const nextThought = description.startsWith(current.thought)
            ? description
            : current.thought + description
          const nextState = activityStateFrom(status, detail)
          updated[idx] = {
            ...current,
            collapsed: collapsedOnTransition(current, nextState),
            detail,
            endedAt:
              nextState === 'completed' ? (current.endedAt ?? new Date().toISOString()) : undefined,
            state: nextState,
            thought: nextThought,
          }
          return {
            activitiesByExecution: {
              ...state.activitiesByExecution,
              [executionId]: updated,
            },
          }
        }
        const withClosed = closeOpenActivities(existing, messageId)
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
            [executionId]: appendActivity(withClosed, activity),
          },
        }
      })
    } else if (activityType === 'RESEARCH' || activityType === 'EDITED') {
      set((state) => {
        const existing = state.activitiesByExecution[executionId] ?? []
        const withClosed = closeOpenActivities(existing, actionId)
        const idx = actionId
          ? withClosed.findIndex((a) => a.type === 'tool_execution' && a.actionId === actionId)
          : -1
        if (idx >= 0) {
          const updated = [...withClosed]
          const current = updated[idx] as ToolExecutionActivity
          const nextState = nextActivityState(current, status, detail)
          updated[idx] = {
            ...current,
            ...withHitlApproval(
              {
                approvalRequired: false,
                ...current,
              },
              detail?.hitl,
            ),
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
          approvalRequired: detail?.hitl?.kind === 'approval' && !detail.hitl.response,
          approved: detail?.hitl?.response === 'approved',
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
            [executionId]: appendActivity(withClosed, activity),
          },
        }
      })
    } else if (activityType === 'COMMAND') {
      set((state) => {
        const existing = state.activitiesByExecution[executionId] ?? []
        const withClosed = closeOpenActivities(existing, actionId)
        const command = getDetailCommand(detail) ?? description
        const idx = actionId
          ? withClosed.findIndex((a) => a.type === 'command_execution' && a.actionId === actionId)
          : -1
        if (idx >= 0) {
          const updated = [...withClosed]
          const current = updated[idx] as CommandExecutionActivity
          const nextState = nextActivityState(current, status, detail)
          updated[idx] = {
            ...current,
            ...withHitlApproval(current, detail?.hitl),
            collapsed: collapsedOnTransition(current, nextState),
            command,
            detail,
            exitCode: detail?.exitCode ?? current.exitCode,
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
          approvalRequired: detail?.hitl?.kind === 'approval' && !detail.hitl.response,
          approved: detail?.hitl?.response === 'approved',
          collapsed: false,
          command,
          detail,
          executionId,
          exitCode: detail?.exitCode,
          id: nextActivityId(),
          output: [],
          startedAt: new Date().toISOString(),
          state: activityStateFrom(status, detail),
          type: 'command_execution',
        }
        return {
          activitiesByExecution: {
            ...state.activitiesByExecution,
            [executionId]: appendActivity(withClosed, activity),
          },
        }
      })
    } else {
      set((state) => {
        const existing = state.activitiesByExecution[executionId] ?? []
        const hitl = detail?.hitl
        const hitlId = actionId ?? hitl?.hitlId
        const idx = hitlId
          ? existing.findIndex((a) => a.type === 'elicitation' && a.hitlId === hitlId)
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
        const withClosed = closeOpenActivities(existing, actionId)
        const activity: ElicitationActivity = {
          actionId,
          collapsed: false,
          content: hitl?.content,
          detail,
          executionId,
          form: hitl?.form,
          hitlId: hitlId ?? '',
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
            [executionId]: appendActivity(withClosed, activity),
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

  handleExecutionOutput: (result: ExecutionOutputResult) => {
    set((state) => {
      const activities = state.activitiesByExecution[result.executionId] ?? []
      if (activities.length === 0) {
        return state
      }

      const updated = [...activities]
      for (let i = updated.length - 1; i >= 0; i--) {
        const activity = updated[i]
        if (activity.type === 'command_execution') {
          updated[i] = {
            ...activity,
            output: [...activity.output, result.line],
          }
          break
        }
      }

      return {
        activitiesByExecution: {
          ...state.activitiesByExecution,
          [result.executionId]: updated,
        },
      }
    })
  },

  handleHitlRequired: (result: ExecutionHitlRequiredResult) => {
    if (result.kind === 'question') {
      set((state) => {
        const existing = state.activitiesByExecution[result.executionId] ?? []
        const idx = existing.findIndex(
          (a) => a.type === 'elicitation' && a.hitlId === result.hitlId,
        )
        if (idx >= 0) {
          return state
        }
        const withClosed = closeOpenActivities(existing, result.hitlId)
        const activity: ElicitationActivity = {
          actionId: result.hitlId,
          collapsed: false,
          executionId: result.executionId,
          form: result.form,
          hitlId: result.hitlId,
          id: nextActivityId(),
          message: result.message,
          startedAt: new Date().toISOString(),
          state: 'active',
          type: 'elicitation',
        }
        return {
          activitiesByExecution: {
            ...state.activitiesByExecution,
            [result.executionId]: appendActivity(withClosed, activity),
          },
        }
      })
      return
    }
    set((state) => {
      const existing = state.activitiesByExecution[result.executionId] ?? []
      const withClosed = closeOpenActivities(existing, result.hitlId)
      const permission = {
        permissionDiff: result.diff,
        permissionKind: result.toolKind,
        permissionOptions: result.options,
        permissionSegments: result.commandSegments,
        permissionTitle: result.title,
      }
      const findIdx = (type: 'command_execution' | 'tool_execution') => {
        if (!result.hitlId) return -1
        return withClosed.findIndex((a) => a.type === type && a.actionId === result.hitlId)
      }

      const idx =
        findIdx('command_execution') >= 0 ? findIdx('command_execution') : findIdx('tool_execution')
      if (idx >= 0) {
        const updated = [...withClosed]
        const current = updated[idx]
        if (current.type === 'tool_execution') {
          updated[idx] = {
            ...current,
            ...permission,
            approvalRequired: true,
            state: 'pending_approval' as const,
          }
        } else if (current.type === 'command_execution') {
          updated[idx] = {
            ...current,
            ...permission,
            approvalRequired: true,
            command: result.command ?? current.command,
            state: 'pending_approval' as const,
          }
        }
        return {
          activitiesByExecution: {
            ...state.activitiesByExecution,
            [result.executionId]: updated,
          },
        }
      }
      const activity: CommandExecutionActivity = {
        actionId: result.hitlId,
        approvalRequired: true,
        collapsed: false,
        command: result.command ?? result.message,
        executionId: result.executionId,
        id: nextActivityId(),
        output: [],
        ...permission,
        startedAt: new Date().toISOString(),
        state: 'pending_approval',
        type: 'command_execution',
      }
      return {
        activitiesByExecution: {
          ...state.activitiesByExecution,
          [result.executionId]: appendActivity(withClosed, activity),
        },
      }
    })
  },

  handleHitlResolved: (result: ExecutionHitlResolvedResult) => {
    if (result.kind === 'question') {
      set((state) => {
        const activities = state.activitiesByExecution[result.executionId] ?? []
        if (activities.length === 0) {
          return state
        }

        const updated = [...activities]
        for (let i = updated.length - 1; i >= 0; i--) {
          const activity = updated[i]
          if (activity.type === 'elicitation' && activity.hitlId === result.hitlId) {
            const nextState = result.response === 'answered' ? 'completed' : 'error'
            updated[i] = {
              ...activity,
              collapsed: collapsedOnTransition(activity, nextState),
              content: result.content ?? activity.content,
              endedAt: new Date().toISOString(),
              response: result.response,
              state: nextState,
            }
            break
          }
        }

        return {
          activitiesByExecution: {
            ...state.activitiesByExecution,
            [result.executionId]: updated,
          },
        }
      })
      return
    }
    set((state) => {
      const activities = state.activitiesByExecution[result.executionId] ?? []
      if (activities.length === 0) {
        return state
      }

      const updated = [...activities]
      for (let i = updated.length - 1; i >= 0; i--) {
        const activity = updated[i]
        if (
          (activity.type === 'command_execution' || activity.type === 'tool_execution') &&
          activity.state === 'pending_approval'
        ) {
          if (
            activity.actionId === undefined &&
            result.command !== undefined &&
            activityCommand(activity) !== result.command
          ) {
            continue
          }
          if (
            result.hitlId &&
            activity.actionId !== undefined &&
            activity.actionId !== result.hitlId
          ) {
            continue
          }
          const nextState = result.response === 'approved' ? 'active' : 'error'
          updated[i] = {
            ...activity,
            approved: result.response === 'approved',
            collapsed: collapsedOnTransition(activity, nextState),
            hitlResponse: result.response,
            resolvedBy: result.resolvedByDisplayName ?? undefined,
            state: nextState,
          }
          break
        }
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
    hitlId: string,
    response: HitlResponse,
    optionId?: string,
    content?: Record<string, unknown>,
    rules?: CreateHitlRuleRequest[],
    feedback?: string,
  ) => {
    const body: Record<string, unknown> = { executionId, hitlId, response }
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

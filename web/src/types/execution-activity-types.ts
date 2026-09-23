import type {
  ActivityDetail,
  ActivityDiff,
  ActivityHitl,
  ActivityKind,
  ActivityLocation,
  ApprovalOptionKind,
  CommandSegment,
  HitlKind,
  HitlResponse,
  PermissionOption,
  PlanEntry,
} from './websocket-types'

export type {
  ActivityDetail,
  ActivityDiff,
  ActivityHitl,
  ActivityKind,
  ActivityLocation,
  ApprovalOptionKind,
  CommandSegment,
  HitlKind,
  HitlResponse,
  PermissionOption,
  PlanEntry,
}

export type Activity =
  | CommandExecutionActivity
  | ElicitationActivity
  | ErrorActivity
  | MessageActivity
  | PlanActivity
  | ThinkingActivity
  | ToolExecutionActivity

export type ActivityState = 'active' | 'completed' | 'error' | 'pending_approval'

export type ActivityType =
  | 'command_execution'
  | 'elicitation'
  | 'error'
  | 'message'
  | 'plan'
  | 'thinking'
  | 'tool_execution'

export interface BaseActivity {
  actionId?: string
  approvalRequired?: boolean
  approved?: boolean
  collapsed: boolean
  endedAt?: string
  executionId: string
  hitlResponse?: HitlResponse
  id: string
  permissionDiff?: ActivityDiff
  permissionKind?: string
  permissionOptions?: PermissionOption[]
  permissionSegments?: CommandSegment[]
  permissionTitle?: string
  resolvedBy?: string
  startedAt: string
  state: ActivityState
  type: ActivityType
}

export interface CommandExecutionActivity extends BaseActivity {
  approvalRequired: boolean
  approved?: boolean
  command: string
  detail?: ActivityDetail
  exitCode?: number
  type: 'command_execution'
}

export interface ElicitationActivity extends BaseActivity {
  content?: Record<string, unknown>
  detail?: ActivityDetail
  form?: Record<string, unknown>
  hitlId: string
  message: string
  response?: HitlResponse
  type: 'elicitation'
}

export interface ErrorActivity extends BaseActivity {
  detail?: ActivityDetail
  exitCode?: number
  message: string
  type: 'error'
}

export interface MessageActivity extends BaseActivity {
  detail?: ActivityDetail
  role: 'agent' | 'user'
  text: string
  type: 'message'
}

export interface PlanActivity extends BaseActivity {
  detail?: ActivityDetail
  plan: PlanEntry[]
  type: 'plan'
}

export interface ThinkingActivity extends BaseActivity {
  detail?: ActivityDetail
  thought: string
  type: 'thinking'
}

export interface ToolExecutionActivity extends BaseActivity {
  detail?: ActivityDetail
  error?: string
  result?: string
  taskId: string
  thought: string
  toolName: string
  type: 'tool_execution'
}

export function isPendingApproval(activity: Activity): activity is CommandExecutionActivity {
  return activity.type === 'command_execution' && activity.state === 'pending_approval'
}

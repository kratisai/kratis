import type {
  ActivityAttachment,
  ActivityDetail,
  ActivityDiff,
  ActivityHitl,
  ActivityKind,
  ActivityLocation,
  ApprovalOptionKind,
  CommandSegment,
  HitlKind,
  HitlResolver,
  HitlResolverKind,
  HitlResponse,
  PermissionOption,
  PlanEntry,
} from './websocket-types'

export type {
  ActivityAttachment,
  ActivityDetail,
  ActivityDiff,
  ActivityHitl,
  ActivityKind,
  ActivityLocation,
  ApprovalOptionKind,
  CommandSegment,
  HitlKind,
  HitlResolver,
  HitlResolverKind,
  HitlResponse,
  PermissionOption,
  PlanEntry,
}

export type Activity =
  | AttachmentActivity
  | CommandExecutionActivity
  | ElicitationActivity
  | EndTurnActivity
  | ErrorActivity
  | MessageActivity
  | PlanActivity
  | PublishActivity
  | ThinkingActivity
  | ToolExecutionActivity
  | WarningActivity

export type ActivityState = 'active' | 'completed' | 'error' | 'pending_approval'

export type ActivityType =
  | 'attachment'
  | 'command_execution'
  | 'elicitation'
  | 'end_turn'
  | 'error'
  | 'message'
  | 'plan'
  | 'publish'
  | 'thinking'
  | 'tool_execution'
  | 'warning'

export interface AttachmentActivity extends BaseActivity {
  attachment?: ActivityAttachment
  description: string
  detail?: ActivityDetail
  type: 'attachment'
}

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
  permissionParseWarning?: string
  permissionSegments?: CommandSegment[]
  permissionTitle?: string
  resolvedBy?: HitlResolver
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
  message: string
  response?: HitlResponse
  type: 'elicitation'
}

export interface EndTurnActivity extends BaseActivity {
  description: string
  type: 'end_turn'
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

export interface PublishActivity extends BaseActivity {
  baseBranch?: string
  commitSha?: string
  description: string
  detail?: ActivityDetail
  headBranch?: string
  isUpdate?: boolean
  previewLabel?: string
  previewUrl?: string
  prNumber?: number
  providerType?: string
  prUrl?: string
  type: 'publish'
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

export interface WarningActivity extends BaseActivity {
  detail?: ActivityDetail
  message: string
  type: 'warning'
}

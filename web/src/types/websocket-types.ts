// JSON-RPC 2.0 WebSocket message types for Kratis API

import type { IngestionStatus } from './auth-types'
import type { CanvasEvent } from './canvas-types'

export interface ActivityDetail {
  diff?: ActivityDiff
  exitCode?: number
  hitl?: ActivityHitl
  input?: Record<string, unknown>
  kind?: ActivityKind
  locations?: ActivityLocation[]
  messageId?: string
  meta?: Record<string, unknown>
  output?: string
  plan?: PlanEntry[]
  rawUpdate?: Record<string, unknown>
  role?: string
  title?: string
  truncated?: boolean
}

export interface ActivityDiff {
  newText?: string
  oldText?: string
  path?: string
}

export interface ActivityHitl {
  approved?: boolean
  cancelled?: boolean
  command?: string
  content?: Record<string, unknown>
  diff?: ActivityDiff
  form?: Record<string, unknown>
  hitlId: string
  kind: HitlKind
  message: string
  optionId?: string
  options?: PermissionOption[]
  response?: HitlResponse
  title?: string
  toolKind?: string
}

export type ActivityKind =
  | 'delete'
  | 'edit'
  | 'execute'
  | 'fetch'
  | 'move'
  | 'other'
  | 'read'
  | 'search'
  | 'switch_mode'
  | 'think'

export interface ActivityLocation {
  line?: number
  path?: string
}

export type ApprovalOptionKind = 'allow_always' | 'allow_once' | 'reject_always' | 'reject_once'

export interface AuthParams {
  token: string
}
export interface AuthResult {
  status: 'authenticated'
  type: 'auth'
  userId: string
}

export interface CanvasResult {
  event: CanvasEvent
  type: 'canvas'
}

export interface ChatErrorResult {
  chatId: string
  code: number
  message: string
  messageId: string
  type: 'chat_error'
}

export interface ChatSendParams {
  chatId: string
  message: string
  modelName: string
  providerId: string
  teamId: string
}

export interface ChatSubscribeParams {
  chatId: string
  teamId?: string
}

export interface ChatSubscriptionResult {
  chatId: string
  status: string
  type: 'chat_subscription'
}

export interface ChatUnsubscribeParams {
  chatId: string
}

export interface ClientRpcMethods {
  auth: AuthParams
  'chat.send': ChatSendParams
  'chat.subscribe': ChatSubscribeParams
  'chat.unsubscribe': ChatUnsubscribeParams
  'execution.replay_activities': ExecutionReplayActivitiesParams
  ping: Record<string, never> | void
  subscribe: SubscribeParams
  unsubscribe: SubscribeParams
}

export interface CompleteResult {
  chatId?: string
  messageCount?: number
  messageId?: string
  type: 'complete'
}

export interface ExecutionActivityResult {
  actionId?: string
  activityType: WireActivityType
  description: string
  detail?: ActivityDetail
  executionId: string
  status: WireActivityStatus
  type: 'execution_activity'
}

export interface ExecutionCompleteResult {
  executionId: string
  exitCode: number
  status: string
  type: 'execution_complete'
}

export interface ExecutionHitlRequiredResult {
  command?: string
  content?: Record<string, unknown>
  diff?: ActivityDiff
  executionId: string
  form?: Record<string, unknown>
  hitlId: string
  kind: HitlKind
  message: string
  options?: PermissionOption[]
  title?: string
  toolKind?: string
  type: 'execution_hitl_required'
}

export interface ExecutionHitlResolvedResult {
  command?: string
  content?: Record<string, unknown>
  executionId: string
  hitlId: string
  kind: HitlKind
  optionId?: null | string
  resolvedByDisplayName?: null | string
  resolvedByUserId?: null | string
  response: HitlResponse
  type: 'execution_hitl_resolved'
}

export interface ExecutionOutputResult {
  executionId: string
  line: string
  stream: WireOutputStream
  type: 'execution_output'
}

export interface ExecutionReplayActivitiesParams {
  executionId: string
}

export interface ExecutionReplayCompleteResult {
  activityCount: number
  executionId: string
  type: 'execution_replay_complete'
}

export interface ExecutionStatusChangedResult {
  chatId: string
  executionId: string
  teamId: string
  type: 'execution_status_changed'
}

export type HitlKind = 'approval' | 'question'

export type HitlResponse = 'answered' | 'approved' | 'cancelled' | 'declined'

export interface IngestionEventPayload {
  batchId: string
  commitHash: null | string
  completedAt: null | string
  repositoryId: string
  status: IngestionStatus
}

export interface IngestionResult {
  event: IngestionEventPayload
  type: 'ingestion'
}

export interface JsonRpcError {
  code: number
  data?: unknown
  message: string
}

export interface JsonRpcRequest {
  id: number | string
  jsonrpc: '2.0'
  method: string
  params?: Record<string, unknown>
}

export interface JsonRpcResponse<T extends JsonRpcResult = JsonRpcResult> {
  error?: JsonRpcError
  id: null | number
  jsonrpc: '2.0'
  result?: T
}

export type JsonRpcResult =
  | AuthResult
  | CanvasResult
  | ChatErrorResult
  | ChatSubscriptionResult
  | CompleteResult
  | ExecutionActivityResult
  | ExecutionCompleteResult
  | ExecutionHitlRequiredResult
  | ExecutionHitlResolvedResult
  | ExecutionOutputResult
  | ExecutionReplayCompleteResult
  | ExecutionStatusChangedResult
  | IngestionResult
  | MessageChunkResult
  | MessageResult
  | PingResult
  | SubscriptionResult
  | TeamEntityChangedResult
  | TelemetryResult
  | UserEntityChangedResult

export interface MessageChunkResult {
  chatId?: string
  content: string
  messageId: string
  role: 'assistant' | 'system' | 'user'
  timestamp: string
  type: 'message_chunk'
}

export interface MessageResult {
  chatId?: string
  content: string
  messageId: string
  role: 'assistant' | 'system' | 'user'
  timestamp: string
  type: 'message'
}

export interface PermissionOption {
  kind: ApprovalOptionKind
  name: string
  optionId: string
}

export interface PingResult {
  status: 'pong'
  type: 'ping'
}

export interface PlanEntry {
  content: string
  priority: PlanEntryPriority
  status: PlanEntryStatus
}

export type PlanEntryPriority = 'high' | 'low' | 'medium'

export type PlanEntryStatus = 'completed' | 'in_progress' | 'pending'

export interface SubscribeParams {
  teamId: string
}

export interface SubscriptionResult {
  channel: string
  message: string
  type: 'subscription'
}

export interface TeamEntityChangedResult {
  entity: TeamEntityType
  teamId: string
  type: 'team_entity_changed'
}

export type TeamEntityType =
  | 'CHATS'
  | 'CREDENTIALS'
  | 'ENVIRONMENTS'
  | 'MODEL_PROVIDERS'
  | 'PERMISSIONS'
  | 'REPOSITORIES'
  | 'SANDBOX_EXECUTIONS'
  | 'USAGE'

export type TelemetryEvent = ThoughtEvent | ToolCompleteEvent | ToolErrorEvent | ToolStartEvent

export interface TelemetryResult {
  chatId: string
  event: TelemetryEvent
  type: 'telemetry'
}

export interface ThoughtEvent {
  text: string
}

export interface ToolCompleteEvent {
  status: string
  taskId: string
}

export interface ToolErrorEvent {
  error: string
  taskId: string
}

export interface ToolStartEvent {
  taskId: string
  thought: string
  toolName: string
}

export interface UserEntityChangedResult {
  entity: UserEntityType
  type: 'user_entity_changed'
  userId: string
}

export type UserEntityType = 'PROFILE' | 'TEAMS'

export type WireActivityStatus = 'completed' | 'failed' | 'in_progress' | 'pending'

export type WireActivityType =
  | 'COMMAND'
  | 'EDITED'
  | 'ELICITATION'
  | 'MESSAGE'
  | 'PLAN'
  | 'RESEARCH'
  | 'THINKING'

export type WireOutputStream = 'stderr' | 'stdout'

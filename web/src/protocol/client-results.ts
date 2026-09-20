import { z } from 'zod'

const uuid = z.string().uuid()

const activityKind = z.enum([
  'read',
  'edit',
  'delete',
  'move',
  'search',
  'execute',
  'think',
  'fetch',
  'switch_mode',
  'other',
])

const approvalOptionKind = z.enum(['allow_always', 'allow_once', 'reject_always', 'reject_once'])

const permissionOption = z
  .object({ kind: approvalOptionKind, name: z.string(), optionId: z.string() })
  .strict()

const commandSegment = z
  .object({
    ruleType: z.enum(['EXACT', 'PREFIX_WILD', 'TOOL_KIND']).optional(),
    suggestedRoot: z.string(),
    text: z.string(),
  })
  .strict()

const permissionDiffSchema = z
  .object({
    newText: z.string().optional(),
    oldText: z.string().optional(),
    path: z.string().optional(),
  })
  .passthrough()
  .optional()

const hitlKind = z.enum(['approval', 'question'])
const hitlResponse = z.enum(['approved', 'answered', 'declined', 'cancelled'])

const planEntryPriority = z.enum(['high', 'medium', 'low'])
const planEntryStatus = z.enum(['pending', 'in_progress', 'completed'])

const planEntrySchema = z
  .object({
    content: z.string(),
    priority: planEntryPriority,
    status: planEntryStatus,
  })
  .passthrough()

const hitlSchema = z
  .object({
    command: z.string().optional(),
    commandSegments: z.array(commandSegment).optional(),
    content: z.record(z.string(), z.unknown()).optional(),
    diff: permissionDiffSchema,
    form: z.record(z.string(), z.unknown()).optional(),
    hitlId: z.string(),
    kind: hitlKind,
    message: z.string(),
    optionId: z.string().optional(),
    options: z.array(permissionOption).optional(),
    response: hitlResponse.optional(),
    title: z.string().optional(),
    toolKind: z.string().optional(),
  })
  .passthrough()
  .optional()

const activityDetailSchema = z
  .object({
    diff: z
      .object({
        newText: z.string().optional(),
        oldText: z.string().optional(),
        path: z.string().optional(),
      })
      .passthrough()
      .optional(),
    exitCode: z.number().int().optional(),
    hitl: hitlSchema,
    input: z.record(z.string(), z.unknown()).optional(),
    kind: activityKind.optional(),
    locations: z
      .array(
        z.object({ line: z.number().int().optional(), path: z.string().optional() }).passthrough(),
      )
      .optional(),
    messageId: z.string().optional(),
    meta: z.record(z.string(), z.unknown()).optional(),
    output: z.string().optional(),
    plan: z.array(planEntrySchema).optional(),
    rawUpdate: z.record(z.string(), z.unknown()).optional(),
    role: z.string().optional(),
    title: z.string().optional(),
    truncated: z.boolean().optional(),
  })
  .passthrough()

const telemetryEvents = [
  z.object({ text: z.string() }).strict(),
  z.object({ taskId: z.string(), thought: z.string(), toolName: z.string() }).strict(),
  z.object({ status: z.string(), taskId: z.string() }).strict(),
  z.object({ status: z.string(), taskId: z.string() }).strict(),
  z.object({ error: z.string(), taskId: z.string() }).strict(),
] as const

const canvasEvents = [
  z
    .object({
      canvasType: z.enum(['SPEC', 'DOCUMENT']),
      chatId: uuid,
      content: z.string(),
      documentId: z.string(),
      isNewRepo: z.boolean(),
      repoLabel: z.string().optional(),
      title: z.string(),
    })
    .strict(),
  z
    .object({
      canvasType: z.enum(['SPEC', 'DOCUMENT']),
      chatId: uuid,
      content: z.string(),
      documentId: z.string(),
      isNewRepo: z.boolean(),
      repoLabel: z.string().optional(),
      title: z.string(),
      version: z.number().int(),
    })
    .strict(),
  z.object({ chatId: uuid, documentId: z.string(), version: z.number().int() }).strict(),
  z.object({ chatId: uuid, documentId: z.string() }).strict(),
  z.object({ chatId: uuid, documentId: z.string(), errorMessage: z.string() }).strict(),
] as const

export const CLIENT_RESULTS = {
  auth: z
    .object({ status: z.enum(['authenticated']), type: z.literal('auth'), userId: z.string() })
    .strict(),
  canvas: z.object({ event: z.union(canvasEvents), type: z.literal('canvas') }).strict(),
  chat_error: z
    .object({
      chatId: uuid,
      code: z.number().int(),
      message: z.string(),
      messageId: z.string(),
      type: z.literal('chat_error'),
    })
    .strict(),
  chat_subscription: z
    .object({ chatId: uuid, status: z.string(), type: z.literal('chat_subscription') })
    .strict(),
  complete: z
    .object({
      chatId: uuid.nullable().optional(),
      messageCount: z.number().int(),
      messageId: z.string().nullable().optional(),
      type: z.literal('complete'),
    })
    .strict(),
  execution_acp_initialized: z
    .object({
      agentName: z.string(),
      agentVersion: z.string(),
      executionId: uuid,
      sessionId: z.string(),
      type: z.literal('execution_acp_initialized'),
    })
    .strict(),
  execution_activity: z
    .object({
      actionId: z.string().optional(),
      activityType: z.enum([
        'THINKING',
        'RESEARCH',
        'EDITED',
        'COMMAND',
        'MESSAGE',
        'ELICITATION',
        'PLAN',
      ]),
      description: z.string(),
      detail: activityDetailSchema.optional(),
      executionId: uuid,
      status: z.enum(['pending', 'in_progress', 'completed', 'failed']),
      type: z.literal('execution_activity'),
    })
    .strict(),
  execution_complete: z
    .object({
      executionId: uuid,
      exitCode: z.number().int(),
      status: z.enum(['RUNNING', 'IDLE', 'COMPLETED', 'FAILED']),
      type: z.literal('execution_complete'),
    })
    .strict(),
  execution_hitl_required: z
    .object({
      command: z.string().optional(),
      commandSegments: z.array(commandSegment).optional(),
      diff: permissionDiffSchema,
      executionId: uuid,
      form: z.record(z.string(), z.unknown()).optional(),
      hitlId: z.string(),
      kind: hitlKind,
      message: z.string(),
      options: z.array(permissionOption).optional(),
      title: z.string().optional(),
      toolKind: z.string().optional(),
      type: z.literal('execution_hitl_required'),
    })
    .strict(),
  execution_hitl_resolved: z
    .object({
      content: z.record(z.string(), z.unknown()).optional(),
      executionId: uuid,
      hitlId: z.string(),
      kind: hitlKind,
      optionId: z.string().nullable().optional(),
      resolvedByDisplayName: z.string().nullable().optional(),
      resolvedByUserId: uuid.nullable().optional(),
      response: hitlResponse,
      type: z.literal('execution_hitl_resolved'),
    })
    .strict(),
  execution_output: z
    .object({
      executionId: uuid,
      line: z.string(),
      stream: z.enum(['stdout', 'stderr']),
      type: z.literal('execution_output'),
    })
    .strict(),
  execution_replay_complete: z
    .object({
      activityCount: z.number().int(),
      executionId: uuid,
      type: z.literal('execution_replay_complete'),
    })
    .strict(),
  execution_status_changed: z
    .object({
      chatId: uuid,
      executionId: uuid,
      teamId: uuid,
      type: z.literal('execution_status_changed'),
    })
    .strict(),
  ingestion: z
    .object({
      event: z
        .object({
          batchId: uuid,
          commitHash: z.string().nullable().optional(),
          completedAt: z.string().nullable().optional(),
          repositoryId: uuid,
          status: z.enum(['QUEUED', 'PROCESSING', 'SUCCESS', 'FAILED']),
        })
        .strict(),
      type: z.literal('ingestion'),
    })
    .strict(),
  message: z
    .object({
      chatId: uuid,
      content: z.string(),
      messageId: z.string(),
      role: z.enum(['user', 'assistant', 'system', 'tool']),
      timestamp: z.string(),
      type: z.literal('message'),
    })
    .strict(),
  message_chunk: z
    .object({
      chatId: uuid,
      content: z.string(),
      messageId: z.string(),
      role: z.enum(['user', 'assistant', 'system', 'tool']),
      timestamp: z.string(),
      type: z.literal('message_chunk'),
    })
    .strict(),
  ping: z.object({ status: z.enum(['pong']), type: z.literal('ping') }).strict(),
  subscription: z
    .object({ channel: z.string(), message: z.string(), type: z.literal('subscription') })
    .strict(),
  team_entity_changed: z
    .object({
      entity: z.enum([
        'REPOSITORIES',
        'CREDENTIALS',
        'MODEL_PROVIDERS',
        'ENVIRONMENTS',
        'SANDBOX_EXECUTIONS',
        'CHATS',
        'PERMISSIONS',
      ]),
      teamId: uuid,
      type: z.literal('team_entity_changed'),
    })
    .strict(),
  telemetry: z
    .object({ chatId: uuid, event: z.union(telemetryEvents), type: z.literal('telemetry') })
    .strict(),
  user_entity_changed: z
    .object({
      entity: z.enum(['TEAMS', 'PROFILE']),
      type: z.literal('user_entity_changed'),
      userId: uuid,
    })
    .strict(),
} as const

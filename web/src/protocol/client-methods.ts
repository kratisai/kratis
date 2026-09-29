import { z } from 'zod'

import type { ClientRpcMethods } from '@/types/websocket-types'

export const MessageKind = {
  Notification: 'notification',
  Request: 'request',
} as const

export const Direction = {
  WebToControlPlane: 'web-to-control-plane',
} as const

export type Direction = (typeof Direction)[keyof typeof Direction]
export type MessageKind = (typeof MessageKind)[keyof typeof MessageKind]

export const authParamsSchema = z
  .object({
    token: z.string(),
  })
  .strict()

export const subscribeParamsSchema = z
  .object({
    teamId: z.string(),
  })
  .strict()

export const chatSendParamsSchema = z
  .object({
    chatId: z.string(),
    message: z.string(),
    modelName: z.string(),
    providerId: z.string(),
    teamId: z.string(),
  })
  .strict()

export const chatSubscribeParamsSchema = z
  .object({
    chatId: z.string(),
    teamId: z.string().optional(),
  })
  .strict()

export const chatUnsubscribeParamsSchema = z
  .object({
    chatId: z.string(),
  })
  .strict()

export const executionReplayActivitiesParamsSchema = z
  .object({
    executionId: z.string().uuid(),
  })
  .strict()

export const pingParamsSchema = z.union([z.record(z.never()), z.undefined()])

export interface ClientMethodDefinition {
  direction: Direction
  messageKind: MessageKind
  paramsSchema: z.ZodType<unknown>
}

export const CLIENT_METHODS = {
  auth: {
    direction: Direction.WebToControlPlane,
    messageKind: MessageKind.Request,
    paramsSchema: authParamsSchema,
  },
  'chat.send': {
    direction: Direction.WebToControlPlane,
    messageKind: MessageKind.Request,
    paramsSchema: chatSendParamsSchema,
  },
  'chat.subscribe': {
    direction: Direction.WebToControlPlane,
    messageKind: MessageKind.Request,
    paramsSchema: chatSubscribeParamsSchema,
  },
  'chat.unsubscribe': {
    direction: Direction.WebToControlPlane,
    messageKind: MessageKind.Request,
    paramsSchema: chatUnsubscribeParamsSchema,
  },
  'execution.replay_activities': {
    direction: Direction.WebToControlPlane,
    messageKind: MessageKind.Request,
    paramsSchema: executionReplayActivitiesParamsSchema,
  },
  ping: {
    direction: Direction.WebToControlPlane,
    messageKind: MessageKind.Request,
    paramsSchema: pingParamsSchema,
  },
  subscribe: {
    direction: Direction.WebToControlPlane,
    messageKind: MessageKind.Request,
    paramsSchema: subscribeParamsSchema,
  },
  unsubscribe: {
    direction: Direction.WebToControlPlane,
    messageKind: MessageKind.Request,
    paramsSchema: subscribeParamsSchema,
  },
} as const

export type ClientMethod = keyof typeof CLIENT_METHODS

// Static type assertion: the runtime registry must exactly match the type-level method map.
// If a method is added to ClientRpcMethods but not to CLIENT_METHODS (or vice versa), typecheck fails.
type AssertEqual<A, B> = [A] extends [B] ? ([B] extends [A] ? true : never) : never

export const _assertMethodsMatch: AssertEqual<keyof ClientRpcMethods, keyof typeof CLIENT_METHODS> =
  true

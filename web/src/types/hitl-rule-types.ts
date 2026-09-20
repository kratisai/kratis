export interface CreateHitlRuleRequest {
  action?: HitlRuleAction
  commandRoot: string
  ruleType?: HitlRuleType
}

export type HitlRuleAction = 'ALLOW' | 'DENY'

export interface HitlRuleDto {
  action: HitlRuleAction
  commandRoot: string
  createdAt: string
  createdByName: null | string
  createdByUserId: null | string
  id: string
  ruleType: HitlRuleType
  teamId: string
}

export type HitlRuleType = 'EXACT' | 'PREFIX_WILD' | 'TOOL_KIND'

export const TOOL_KIND_VALUES = [
  'read',
  'edit',
  'write',
  'delete',
  'move',
  'search',
  'execute',
  'think',
  'fetch',
  'switch_mode',
  'other',
] as const

export type ToolKindValue = (typeof TOOL_KIND_VALUES)[number]

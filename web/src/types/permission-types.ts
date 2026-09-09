export interface CreateSandboxPermissionRuleRequest {
  action: SandboxPermissionAction
  commandRoot: string
  ruleType: SandboxPermissionRuleType
}

export type SandboxPermissionAction = 'ALLOW' | 'DENY'

export interface SandboxPermissionRuleDto {
  action: SandboxPermissionAction
  commandRoot: string
  createdAt: string
  createdByName: null | string
  createdByUserId: null | string
  id: string
  ruleType: SandboxPermissionRuleType
  teamId: string
}

export type SandboxPermissionRuleType = 'EXACT' | 'PREFIX_WILD'

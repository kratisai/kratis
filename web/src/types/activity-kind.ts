/**
 * Single source of truth for the tool-kind vocabulary shared by activity
 * records, the protocol zod enum, and HITL permission narrowing.
 *
 * The values mirror the ACP ToolKind set (plus `write`, Kratis's alias for
 * fs/write_text_file) and the control-plane `ActivityKind` enum.
 */
export const ACTIVITY_KINDS = [
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

export type ActivityKind = (typeof ACTIVITY_KINDS)[number]

const ACTIVITY_KIND_SET: ReadonlySet<string> = new Set(ACTIVITY_KINDS)

/**
 * Narrows an unconstrained wire `toolKind` to a known ActivityKind.
 * ACP permission requests carry `toolKind` as a plain string, so unknown or
 * absent values degrade to `other` rather than dropping the activity.
 */
export function toActivityKind(toolKind: string | undefined): ActivityKind {
  const candidate = toolKind?.toLowerCase() ?? ''
  return ACTIVITY_KIND_SET.has(candidate) ? (candidate as ActivityKind) : 'other'
}

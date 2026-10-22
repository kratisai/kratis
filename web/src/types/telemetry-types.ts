// WorkingItem - represents a single step in the agent's working process
// Populated from TelemetryResult events in the websocket store

export type WorkingItem = WorkingThought | WorkingTool

export interface WorkingThought {
  attempt: number
  id: string
  text: string
  type: 'thought'
}

export interface WorkingTool {
  attempt: number
  errorMessage?: string
  status: 'complete' | 'error' | 'running'
  taskId: string
  thought: string
  toolName: string
  type: 'tool'
}

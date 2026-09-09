// Canvas event types — mirror the Java CanvasEvent sealed interface
// Used by the Zustand store to handle canvas WebSocket events

export interface CanvasCommitEvent {
  chatId: string
  documentId: string
  version: number
}

export interface CanvasCreateEvent {
  canvasType: CanvasType
  chatId: string
  content: string
  documentId: string
  isNewRepo: boolean
  repoLabel?: string
  title: string
}

export interface CanvasDeleteEvent {
  chatId: string
  documentId: string
}

export interface CanvasDocument {
  canvasType: CanvasType
  chatId: string
  content: string
  documentId: string
  isNewRepo: boolean
  repoLabel?: string
  title: string
  version: number
}

export interface CanvasErrorEvent {
  chatId: string
  documentId: string
  errorMessage: string
}

export type CanvasEvent =
  | CanvasCommitEvent
  | CanvasCreateEvent
  | CanvasDeleteEvent
  | CanvasErrorEvent
  | CanvasUpdateEvent

export type CanvasType = 'DOCUMENT' | 'SPEC'

export interface CanvasUpdateEvent {
  canvasType: CanvasType
  chatId: string
  content: string
  documentId: string
  isNewRepo: boolean
  repoLabel?: string
  title: string
  version: number
}

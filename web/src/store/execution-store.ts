import { create } from 'zustand'

import type { ExecutionLogsResult } from '@/types/websocket-types'

import { useActivityStore } from '@/store/activity-store'
import { useWebSocketStore } from '@/store/websocket-store'

interface ExecutionState {
  addLog: (executionId: string, log: string) => void
  clearLogs: (executionId: string) => void
  handleLogsResult: (result: ExecutionLogsResult) => void
  logs: Record<string, string[]>
  requestLogs: (executionId: string) => void
  setTerminalFullscreen: (fullscreen: boolean) => void
  setTerminalHeight: (height: number) => void
  setTerminalOpen: (open: boolean) => void
  subscribeExecution: (executionId: string) => void
  subscriberCounts: Record<string, number>
  terminalFullscreen: boolean
  terminalHeight: number
  terminalOpen: boolean
  unsubscribeExecution: (executionId: string) => void
}

// Live line count at request time, so lines streamed while catch-up is in flight
// are appended after the history instead of being overwritten by it.
const logBaselines = new Map<string, number>()

export const useExecutionStore = create<ExecutionState>((set, get) => ({
  addLog: (executionId, log) =>
    set((state) => ({
      logs: { ...state.logs, [executionId]: [...(state.logs[executionId] ?? []), log] },
    })),
  clearLogs: (executionId) =>
    set((state) => {
      if (!(executionId in state.logs)) return state
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      const { [executionId]: _removed, ...rest } = state.logs
      return { logs: rest }
    }),
  handleLogsResult: (result) =>
    set((state) => {
      if (result.status !== 'CONNECTED' || result.lines.length === 0) {
        logBaselines.delete(result.executionId)
        return {}
      }
      const current = state.logs[result.executionId] ?? []
      const liveSinceRequest = current.slice(logBaselines.get(result.executionId) ?? current.length)
      logBaselines.delete(result.executionId)
      return {
        logs: { ...state.logs, [result.executionId]: [...result.lines, ...liveSinceRequest] },
      }
    }),
  logs: {},
  requestLogs: (executionId) => {
    logBaselines.set(executionId, (get().logs[executionId] ?? []).length)
    useWebSocketStore.getState().send('execution.get_logs', { executionId })
  },
  setTerminalFullscreen: (terminalFullscreen) => set({ terminalFullscreen }),
  setTerminalHeight: (terminalHeight) => set({ terminalHeight }),
  setTerminalOpen: (open) => set({ terminalOpen: open }),
  subscribeExecution: (executionId) => {
    const count = get().subscriberCounts[executionId] ?? 0
    set((state) => ({
      subscriberCounts: { ...state.subscriberCounts, [executionId]: count + 1 },
    }))
    if (count > 0) return
    // The server replays persisted activities on every new subscription, so the local log is
    // rebuilt from scratch rather than merged with what is already on screen.
    useActivityStore.getState().clearActivities(executionId)
    useWebSocketStore.getState().send('execution.subscribe', { executionId })
  },
  subscriberCounts: {},
  terminalFullscreen: false,
  terminalHeight: 256,
  terminalOpen: false,
  unsubscribeExecution: (executionId) => {
    const count = get().subscriberCounts[executionId] ?? 0
    if (count === 0) return
    if (count > 1) {
      set((state) => ({
        subscriberCounts: { ...state.subscriberCounts, [executionId]: count - 1 },
      }))
      return
    }
    set((state) => {
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      const { [executionId]: _removed, ...rest } = state.subscriberCounts
      return { subscriberCounts: rest }
    })
    useWebSocketStore.getState().send('execution.unsubscribe', { executionId })
  },
}))

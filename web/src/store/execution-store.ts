import { create } from 'zustand'

import type { ExecutionReplayCompleteResult } from '@/types/websocket-types'

import { useActivityStore } from '@/store/activity-store'
import { useWebSocketStore } from '@/store/websocket-store'

interface ExecutionState {
  addLog: (executionId: string, log: string) => void
  clearLogs: (executionId: string) => void
  handleReplayComplete: (result: ExecutionReplayCompleteResult) => void
  logs: Record<string, string[]>
  replayActivities: (executionId: string) => void
  replayingExecutionId: null | string
  setTerminalFullscreen: (fullscreen: boolean) => void
  setTerminalHeight: (height: number) => void
  setTerminalOpen: (open: boolean) => void
  terminalFullscreen: boolean
  terminalHeight: number
  terminalOpen: boolean
}

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
  handleReplayComplete: (result) => {
    if (get().replayingExecutionId === result.executionId) {
      set({ replayingExecutionId: null })
    }
  },
  logs: {},
  replayActivities: (executionId) => {
    useActivityStore.getState().clearActivities(executionId)
    set({ replayingExecutionId: executionId })
    useWebSocketStore.getState().send('execution.replay_activities', { executionId })
  },
  replayingExecutionId: null,
  setTerminalFullscreen: (terminalFullscreen) => set({ terminalFullscreen }),
  setTerminalHeight: (terminalHeight) => set({ terminalHeight }),
  setTerminalOpen: (open) => set({ terminalOpen: open }),
  terminalFullscreen: false,
  terminalHeight: 256,
  terminalOpen: false,
}))

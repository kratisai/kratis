import { create } from 'zustand'
import { persist } from 'zustand/middleware'

import type { ModelProviderDto } from '@/types/auth-types'

const SIDEBAR_AUTO_COLLAPSE_MAX_WIDTH = 900

export const SIDEBAR_WIDTH_DEFAULT = 256
export const SIDEBAR_WIDTH_MAX = 480
export const SIDEBAR_WIDTH_MIN = 208

type SidebarViewport = 'narrow' | 'wide'

interface UIState {
  selectedModelName: null | string
  selectedProviderId: null | string
  setSelectedModel: (providerId: null | string, modelName: null | string) => void
  setSidebarOpen: (open: boolean) => void
  setSidebarWidth: (width: number) => void
  sidebarOpen: boolean
  sidebarViewport: SidebarViewport
  sidebarWidth: number
  syncSelectedModel: (providers: ModelProviderDto[] | undefined) => void
  syncSidebarToViewport: (viewportWidth: number) => void
  toggleSidebar: () => void
}

export function computeInitialSidebarOpen(viewportWidth: number): boolean {
  return viewportWidth >= SIDEBAR_AUTO_COLLAPSE_MAX_WIDTH
}

function clampSidebarWidth(width: number): number {
  return Math.min(SIDEBAR_WIDTH_MAX, Math.max(SIDEBAR_WIDTH_MIN, Math.round(width)))
}

function findFirstChatModel(
  providers: ModelProviderDto[] | undefined,
): null | { modelName: string; providerId: string } {
  for (const provider of providers ?? []) {
    const firstChat = (provider.models ?? []).find((model) => model.kind === 'CHAT')
    if (firstChat) {
      return { modelName: firstChat.modelName, providerId: provider.id }
    }
  }
  return null
}

function isModelAvailable(
  providers: ModelProviderDto[] | undefined,
  providerId: string,
  modelName: string,
): boolean {
  const provider = (providers ?? []).find((entry) => entry.id === providerId)
  return (provider?.models ?? []).some(
    (model) => model.kind === 'CHAT' && model.modelName === modelName,
  )
}

function viewportBucket(viewportWidth: number): SidebarViewport {
  return viewportWidth >= SIDEBAR_AUTO_COLLAPSE_MAX_WIDTH ? 'wide' : 'narrow'
}

export const useUIStore = create<UIState>()(
  persist(
    (set, get) => ({
      selectedModelName: null,
      selectedProviderId: null,
      setSelectedModel: (providerId, modelName) =>
        set({ selectedModelName: modelName, selectedProviderId: providerId }),
      setSidebarOpen: (open) => set({ sidebarOpen: open }),
      setSidebarWidth: (width) => {
        if (!Number.isFinite(width)) return
        set({ sidebarWidth: clampSidebarWidth(width) })
      },
      sidebarOpen: computeInitialSidebarOpen(
        typeof window === 'undefined' ? 1280 : window.innerWidth,
      ),
      sidebarViewport: viewportBucket(typeof window === 'undefined' ? 1280 : window.innerWidth),
      sidebarWidth: SIDEBAR_WIDTH_DEFAULT,
      syncSelectedModel: (providers) => {
        const { selectedModelName, selectedProviderId } = get()
        const firstChatModel = findFirstChatModel(providers)
        if (!firstChatModel) return
        if (selectedProviderId === null || selectedModelName === null) {
          set({
            selectedModelName: firstChatModel.modelName,
            selectedProviderId: firstChatModel.providerId,
          })
          return
        }
        if (!isModelAvailable(providers, selectedProviderId, selectedModelName)) {
          set({
            selectedModelName: firstChatModel.modelName,
            selectedProviderId: firstChatModel.providerId,
          })
        }
      },
      syncSidebarToViewport: (viewportWidth) => {
        const bucket = viewportBucket(viewportWidth)
        if (get().sidebarViewport === bucket) return
        set({ sidebarOpen: bucket === 'wide', sidebarViewport: bucket })
      },
      toggleSidebar: () => set((state) => ({ sidebarOpen: !state.sidebarOpen })),
    }),
    {
      name: 'ui-store',
      partialize: (state) => ({
        selectedModelName: state.selectedModelName,
        selectedProviderId: state.selectedProviderId,
        sidebarWidth: state.sidebarWidth,
      }),
    },
  ),
)

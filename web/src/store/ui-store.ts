import { create } from 'zustand'
import { persist } from 'zustand/middleware'

const SIDEBAR_AUTO_COLLAPSE_MAX_WIDTH = 900

type SidebarViewport = 'narrow' | 'wide'

interface UIState {
  selectedModelName: null | string
  selectedProviderId: null | string
  setSelectedModel: (providerId: null | string, modelName: null | string) => void
  setSidebarOpen: (open: boolean) => void
  sidebarOpen: boolean
  sidebarViewport: SidebarViewport
  syncSidebarToViewport: (viewportWidth: number) => void
  toggleSidebar: () => void
}

export function computeInitialSidebarOpen(viewportWidth: number): boolean {
  return viewportWidth >= SIDEBAR_AUTO_COLLAPSE_MAX_WIDTH
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
      sidebarOpen: computeInitialSidebarOpen(
        typeof window === 'undefined' ? 1280 : window.innerWidth,
      ),
      sidebarViewport: viewportBucket(typeof window === 'undefined' ? 1280 : window.innerWidth),
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
      }),
    },
  ),
)

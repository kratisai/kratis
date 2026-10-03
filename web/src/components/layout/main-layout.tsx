import { Outlet } from '@tanstack/react-router'
import { useEffect, useRef, useState } from 'react'

import { useModelProviders } from '@/hooks/use-model-providers'
import { useAuthStore } from '@/store/auth-store'
import { useUIStore } from '@/store/ui-store'

import { Header } from './header'
import { MobileSidebar } from './mobile-sidebar'
import { Sidebar } from './sidebar'

export function MainLayout() {
  const [mobileOpen, setMobileOpen] = useState(false)

  useDefaultChatModel()

  return (
    <div className="bg-background flex min-h-dvh md:h-dvh" data-testid="main-layout">
      {/* Desktop Sidebar */}
      <div className="hidden md:flex">
        <Sidebar />
      </div>

      {/* Mobile Sidebar */}
      <MobileSidebar onClose={() => setMobileOpen(false)} open={mobileOpen} />

      {/* Main Content */}
      <div className="flex min-w-0 flex-1 flex-col overflow-visible md:overflow-hidden">
        <Header onMobileMenuClick={() => setMobileOpen(true)} />
        <main className="flex-1 overflow-visible md:overflow-hidden">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

function useDefaultChatModel() {
  const currentTeamId = useAuthStore((state) => state.currentTeamId)
  const syncSelectedModel = useUIStore((state) => state.syncSelectedModel)
  const { data: providers } = useModelProviders()
  const lastSyncedRef = useRef<null | string>(null)

  useEffect(() => {
    if (!currentTeamId) {
      lastSyncedRef.current = null
      return
    }
    if (providers === undefined) return
    const fingerprint = JSON.stringify(
      providers.map((provider) => ({
        id: provider.id,
        models: (provider.models ?? [])
          .filter((model) => model.kind === 'CHAT')
          .map((model) => model.modelName),
      })),
    )
    if (lastSyncedRef.current === fingerprint) return
    lastSyncedRef.current = fingerprint
    syncSelectedModel(providers)
  }, [currentTeamId, providers, syncSelectedModel])
}

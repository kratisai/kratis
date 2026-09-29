import { ChevronLeft, Zap } from 'lucide-react'
import { useEffect, useState } from 'react'

import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { cn } from '@/lib/utils'
import { useUIStore } from '@/store/ui-store'

import { SidebarNav } from './sidebar-nav'

export function Sidebar() {
  const sidebarOpen = useUIStore((state) => state.sidebarOpen)
  const syncSidebarToViewport = useUIStore((state) => state.syncSidebarToViewport)
  const toggleSidebar = useUIStore((state) => state.toggleSidebar)
  const [hoverExpanded, setHoverExpanded] = useState(false)

  useEffect(() => {
    const sync = () => syncSidebarToViewport(window.innerWidth)
    sync()
    window.addEventListener('resize', sync)
    return () => window.removeEventListener('resize', sync)
  }, [syncSidebarToViewport])

  const isOverlayMode = !sidebarOpen
  const isOverlayExpanded = isOverlayMode && hoverExpanded
  const effectiveOpen = sidebarOpen || isOverlayExpanded

  return (
    <>
      {isOverlayMode && <div aria-hidden="true" className="w-16 shrink-0" />}
      <aside
        className={cn(
          'border-sidebar-border bg-sidebar flex h-full flex-col overflow-hidden border-r transition-all duration-300',
          isOverlayMode
            ? cn('fixed top-0 bottom-0 left-0 z-50', isOverlayExpanded ? 'w-64 shadow-xl' : 'w-16')
            : 'w-64',
        )}
        onMouseEnter={() => {
          if (isOverlayMode) setHoverExpanded(true)
        }}
        onMouseLeave={() => setHoverExpanded(false)}
      >
        <div className={cn('flex h-full flex-col', effectiveOpen ? 'w-64' : 'w-full')}>
          <div className="flex h-14 items-center justify-between px-4">
            <div
              className={cn('flex items-center gap-2', !effectiveOpen && 'w-full justify-center')}
            >
              <div className="bg-primary flex h-8 w-8 items-center justify-center rounded-lg">
                <Zap className="text-primary-foreground h-4 w-4" />
              </div>
              {effectiveOpen && (
                <span className="text-sidebar-foreground font-semibold">Kratis</span>
              )}
            </div>
            {sidebarOpen && (
              <Button className="h-8 w-8" onClick={toggleSidebar} size="icon" variant="ghost">
                <ChevronLeft className="h-4 w-4" />
              </Button>
            )}
          </div>

          <Separator className="bg-sidebar-border" />

          <SidebarNav showLabels={effectiveOpen} />
        </div>
      </aside>
    </>
  )
}

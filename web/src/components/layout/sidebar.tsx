import { ChevronLeft, Zap } from 'lucide-react'
import { useEffect, useState } from 'react'

import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { cn } from '@/lib/utils'
import {
  SIDEBAR_WIDTH_DEFAULT,
  SIDEBAR_WIDTH_MAX,
  SIDEBAR_WIDTH_MIN,
  useUIStore,
} from '@/store/ui-store'

import { SidebarNav } from './sidebar-nav'

const RESIZE_KEYBOARD_STEP = 16

export function Sidebar() {
  const sidebarOpen = useUIStore((state) => state.sidebarOpen)
  const syncSidebarToViewport = useUIStore((state) => state.syncSidebarToViewport)
  const toggleSidebar = useUIStore((state) => state.toggleSidebar)
  const sidebarWidth = useUIStore((state) => state.sidebarWidth)
  const setSidebarWidth = useUIStore((state) => state.setSidebarWidth)
  const [hoverExpanded, setHoverExpanded] = useState(false)
  const [isResizing, setIsResizing] = useState(false)

  useEffect(() => {
    const sync = () => syncSidebarToViewport(window.innerWidth)
    sync()
    window.addEventListener('resize', sync)
    return () => window.removeEventListener('resize', sync)
  }, [syncSidebarToViewport])

  // Dragging only applies to the pinned sidebar; auto-collapse unmounts the handle mid-drag.
  useEffect(() => {
    if (isResizing && !sidebarOpen) setIsResizing(false)
  }, [isResizing, sidebarOpen])

  useEffect(() => {
    if (!isResizing) return
    const { style } = document.body
    const previousUserSelect = style.userSelect
    const previousCursor = style.cursor
    style.userSelect = 'none'
    style.cursor = 'col-resize'
    return () => {
      style.userSelect = previousUserSelect
      style.cursor = previousCursor
    }
  }, [isResizing])

  const isOverlayMode = !sidebarOpen
  const isOverlayExpanded = isOverlayMode && hoverExpanded
  const effectiveOpen = sidebarOpen || isOverlayExpanded

  const startResizing = (event: React.PointerEvent<HTMLDivElement>) => {
    event.preventDefault()
    event.currentTarget.setPointerCapture(event.pointerId)
    setIsResizing(true)
  }

  const resizeTo = (event: React.PointerEvent<HTMLDivElement>) => {
    if (!isResizing) return
    setSidebarWidth(event.clientX)
  }

  const stopResizing = (event: React.PointerEvent<HTMLDivElement>) => {
    if (!isResizing) return
    event.currentTarget.releasePointerCapture(event.pointerId)
    setIsResizing(false)
  }

  const resizeByStep = (delta: number) => setSidebarWidth(sidebarWidth + delta)

  return (
    <>
      {isOverlayMode && <div aria-hidden="true" className="w-16 shrink-0" />}
      <aside
        className={cn(
          'border-sidebar-border bg-sidebar flex h-full flex-col overflow-hidden border-r transition-all duration-300',
          isOverlayMode
            ? cn('fixed top-0 bottom-0 left-0 z-50', isOverlayExpanded ? 'w-64 shadow-xl' : 'w-16')
            : 'relative',
          isResizing && 'transition-none',
        )}
        onMouseEnter={() => {
          if (isOverlayMode) setHoverExpanded(true)
        }}
        onMouseLeave={() => setHoverExpanded(false)}
        style={isOverlayMode ? undefined : { width: sidebarWidth }}
      >
        <div
          className={cn('flex h-full flex-col', isOverlayMode && effectiveOpen ? 'w-64' : 'w-full')}
        >
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

        {!isOverlayMode && (
          <div
            aria-label="Resize sidebar"
            aria-orientation="vertical"
            aria-valuemax={SIDEBAR_WIDTH_MAX}
            aria-valuemin={SIDEBAR_WIDTH_MIN}
            aria-valuenow={sidebarWidth}
            className={cn(
              'absolute inset-y-0 right-0 z-10 w-1 cursor-col-resize touch-none transition-colors',
              isResizing ? 'bg-sidebar-ring' : 'hover:bg-sidebar-border bg-transparent',
            )}
            data-testid="sidebar-resize-handle"
            onDoubleClick={() => setSidebarWidth(SIDEBAR_WIDTH_DEFAULT)}
            onKeyDown={(event) => {
              if (event.key === 'ArrowLeft') {
                event.preventDefault()
                resizeByStep(-RESIZE_KEYBOARD_STEP)
              } else if (event.key === 'ArrowRight') {
                event.preventDefault()
                resizeByStep(RESIZE_KEYBOARD_STEP)
              } else if (event.key === 'Enter') {
                event.preventDefault()
                setSidebarWidth(SIDEBAR_WIDTH_DEFAULT)
              }
            }}
            onPointerCancel={stopResizing}
            onPointerDown={startResizing}
            onPointerMove={resizeTo}
            onPointerUp={stopResizing}
            role="separator"
            tabIndex={0}
          />
        )}
      </aside>
    </>
  )
}

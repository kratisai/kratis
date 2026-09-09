import { X, Zap } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { cn } from '@/lib/utils'

import { SidebarNav } from './sidebar-nav'

interface MobileSidebarProps {
  onClose: () => void
  open: boolean
}

export function MobileSidebar({ onClose, open }: MobileSidebarProps) {
  if (!open) return null

  return (
    <>
      <div
        className="bg-background/80 fixed inset-0 z-40 backdrop-blur-sm md:hidden"
        onClick={onClose}
      />
      <aside
        className={cn(
          'bg-sidebar fixed inset-y-0 left-0 z-50 flex w-72 flex-col md:hidden',
          'animate-in slide-in-from-left duration-300',
        )}
      >
        <div className="flex h-14 items-center justify-between px-4">
          <div className="flex items-center gap-2">
            <div className="bg-primary flex h-8 w-8 items-center justify-center rounded-lg">
              <Zap className="text-primary-foreground h-4 w-4" />
            </div>
            <span className="text-sidebar-foreground font-semibold">Kratis</span>
          </div>
          <Button onClick={onClose} size="icon" variant="ghost">
            <X className="h-5 w-5" />
          </Button>
        </div>

        <Separator className="bg-sidebar-border" />

        {/* Navigation */}
        <SidebarNav onClose={onClose} showLabels />
      </aside>
    </>
  )
}

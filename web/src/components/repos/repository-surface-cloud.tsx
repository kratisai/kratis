import type { SurfaceRole, SurfaceStatDto } from '@/types/auth-types'

import { HoverCard, HoverCardContent, HoverCardTrigger } from '@/components/ui/hover-card'

const SURFACE_ROLE_STYLES: Record<
  SurfaceRole,
  { bg: string; border: string; label: string; text: string }
> = {
  BIDIRECTIONAL: {
    bg: 'bg-sky-600 dark:bg-sky-800',
    border: 'border-sky-400 dark:border-sky-700',
    label: 'Bidirectional',
    text: 'text-sky-700 dark:text-sky-300',
  },
  PROVIDES: {
    bg: 'bg-emerald-600 dark:bg-emerald-800',
    border: 'border-emerald-400 dark:border-emerald-700',
    label: 'Provides',
    text: 'text-emerald-700 dark:text-emerald-300',
  },
  REQUIRES: {
    bg: 'bg-amber-600 dark:bg-amber-800',
    border: 'border-amber-400 dark:border-amber-700',
    label: 'Requires',
    text: 'text-amber-700 dark:text-amber-300',
  },
  UNKNOWN: {
    bg: 'bg-slate-600 dark:bg-slate-700',
    border: 'border-slate-400 dark:border-slate-600',
    label: 'Unknown',
    text: 'text-slate-700 dark:text-slate-300',
  },
}

const ROLE_ORDER: SurfaceRole[] = ['PROVIDES', 'REQUIRES', 'BIDIRECTIONAL', 'UNKNOWN']

export function RepositorySurfaceCloud({ surfaces }: { surfaces: SurfaceStatDto[] }) {
  const sorted = [...surfaces].sort((a, b) => {
    const roleDelta = ROLE_ORDER.indexOf(a.role) - ROLE_ORDER.indexOf(b.role)
    return roleDelta === 0 ? a.description.localeCompare(b.description) : roleDelta
  })

  return (
    <div className="mt-2 flex flex-wrap gap-1">
      {sorted.map((surface) => {
        const style = SURFACE_ROLE_STYLES[surface.role]
        return (
          <HoverCard
            closeDelay={100}
            key={`${surface.role}:${surface.description}:${surface.detectedBy}`}
            openDelay={150}
          >
            <HoverCardTrigger asChild>
              <span
                className={`inline-flex max-w-64 cursor-default items-center overflow-hidden rounded border ${style.border}`}
                style={{ fontSize: '10px', lineHeight: '1.4' }}
                title={surface.description}
              >
                <span
                  className={`${style.bg} max-w-full truncate px-1 py-px font-medium text-white dark:text-white`}
                >
                  {surface.description}
                </span>
              </span>
            </HoverCardTrigger>
            <HoverCardContent>
              <SurfaceHoverCardBody surface={surface} />
            </HoverCardContent>
          </HoverCard>
        )
      })}
    </div>
  )
}

function SurfaceHoverCardBody({ surface }: { surface: SurfaceStatDto }) {
  const style = SURFACE_ROLE_STYLES[surface.role]
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <span className={`text-xs font-semibold ${style.text}`}>{style.label}</span>
        <span className="text-muted-foreground shrink-0 text-xs">
          {surface.attributeCount} attribute{surface.attributeCount === 1 ? '' : 's'}
        </span>
      </div>
      <p className="text-sm leading-relaxed">{surface.description}</p>
      <p className="text-muted-foreground text-xs">Detected by {surface.detectedBy}</p>
    </div>
  )
}

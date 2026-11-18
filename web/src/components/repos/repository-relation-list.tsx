import type { LucideIcon } from 'lucide-react'

import { Link } from '@tanstack/react-router'
import { Blocks, Database, Info, RadioTower, Server, Waypoints, Webhook } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import type { RelationNature, RelationStatDto } from '@/types/auth-types'

import { Badge } from '@/components/ui/badge'
import { HoverCard, HoverCardContent, HoverCardTrigger } from '@/components/ui/hover-card'
import { relationActionLabel } from '@/lib/relation-format'

const RELATION_ICONS: Record<RelationNature, LucideIcon> = {
  BUILDS_AGAINST: Blocks,
  CALLS: Webhook,
  HOSTS: Server,
  MESSAGES: RadioTower,
  PEER: Waypoints,
  SHARES_STATE: Database,
}

const RELATION_ICON_STYLES: Record<RelationNature, string> = {
  BUILDS_AGAINST: 'text-amber-600 dark:text-amber-400',
  CALLS: 'text-sky-600 dark:text-sky-400',
  HOSTS: 'text-emerald-600 dark:text-emerald-400',
  MESSAGES: 'text-violet-600 dark:text-violet-400',
  PEER: 'text-rose-600 dark:text-rose-400',
  SHARES_STATE: 'text-cyan-600 dark:text-cyan-400',
}

export function RepositoryRelationList({ relations }: { relations: RelationStatDto[] }) {
  return (
    <ul className="mt-2 space-y-1.5">
      {relations.map((relation) => {
        const Icon = RELATION_ICONS[relation.nature]
        const rationales = (relation.rationales ?? []).filter((rationale) => rationale.trim())
        return (
          <li
            className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-sm"
            key={`${relation.otherRepositoryId}:${relation.nature}`}
          >
            <Icon
              aria-hidden="true"
              className={`h-4 w-4 shrink-0 ${RELATION_ICON_STYLES[relation.nature]}`}
            />
            <span className="text-muted-foreground">
              {relationActionLabel(relation.nature, relation.currentSide)}
            </span>
            <Link
              className="text-foreground font-medium underline-offset-4 hover:underline"
              params={{ repoId: relation.otherRepositoryId }}
              to="/repos/$repoId"
            >
              {relation.otherRepositoryName}
            </Link>
            {rationales.length > 0 && (
              <RelationRationales
                rationales={rationales}
                repositoryName={relation.otherRepositoryName}
              />
            )}
            {relation.confidence === 'SUGGESTED' && (
              <Badge className="text-[10px]" variant="outline">
                Suggested
              </Badge>
            )}
          </li>
        )
      })}
    </ul>
  )
}

function RelationRationales({
  rationales,
  repositoryName,
}: {
  rationales: string[]
  repositoryName: string
}) {
  const [pinned, setPinned] = useState(false)
  const [transientOpen, setTransientOpen] = useState(false)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const contentRef = useRef<HTMLDivElement>(null)
  const open = pinned || transientOpen

  useEffect(() => {
    if (!open) {
      return
    }
    const dismiss = () => {
      setPinned(false)
      setTransientOpen(false)
    }
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node
      if (triggerRef.current?.contains(target) || contentRef.current?.contains(target)) {
        return
      }
      dismiss()
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        dismiss()
      }
    }
    document.addEventListener('pointerdown', onPointerDown, true)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown, true)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  return (
    <HoverCard closeDelay={100} onOpenChange={setTransientOpen} open={open} openDelay={150}>
      <HoverCardTrigger asChild>
        <button
          aria-expanded={open}
          aria-label={`Why ${repositoryName} is related`}
          className="text-muted-foreground hover:text-foreground inline-flex h-4 w-4 cursor-pointer items-center justify-center rounded-full"
          onBlur={() => {
            if (!pinned) {
              setTransientOpen(false)
            }
          }}
          onClick={() => {
            setPinned((previous) => !previous)
            setTransientOpen(false)
          }}
          onFocus={() => setTransientOpen(true)}
          ref={triggerRef}
          type="button"
        >
          <Info aria-hidden="true" className="h-3.5 w-3.5" />
        </button>
      </HoverCardTrigger>
      <HoverCardContent ref={contentRef}>
        <div className="space-y-2">
          <p className="text-sm font-semibold">Relation rationale</p>
          <ul className="space-y-1">
            {rationales.map((rationale) => (
              <li className="text-muted-foreground text-xs leading-relaxed" key={rationale}>
                {rationale}
              </li>
            ))}
          </ul>
        </div>
      </HoverCardContent>
    </HoverCard>
  )
}

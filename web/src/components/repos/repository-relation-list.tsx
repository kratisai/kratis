import type { LucideIcon } from 'lucide-react'

import { Link } from '@tanstack/react-router'
import { Blocks, Database, RadioTower, Server, Waypoints, Webhook } from 'lucide-react'

import type { RelationNature, RelationStatDto } from '@/types/auth-types'

import { Badge } from '@/components/ui/badge'
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

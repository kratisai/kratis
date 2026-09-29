import { useNavigate, useParams } from '@tanstack/react-router'
import { ArrowLeft, BookOpen, ChevronRight, FileText, Loader2 } from 'lucide-react'
import { useEffect, useState } from 'react'

import type { WikiPageDto } from '@/lib/wiki-api'

import { Button } from '@/components/ui/button'
import { useRepositories } from '@/hooks/use-repositories'
import { useWikiPage, useWikiTree } from '@/hooks/use-wiki'
import { cn } from '@/lib/utils'

interface WikiSidebarSectionProps {
  onClose?: () => void
}

export function WikiSidebarSection({ onClose }: WikiSidebarSectionProps) {
  // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-assertion -- strict:false params may omit keys at runtime
  const params = useParams({ strict: false }) as { pageSlug?: string; repoId?: string }
  const { pageSlug, repoId } = params
  const navigate = useNavigate()

  const { data: repositories } = useRepositories()
  const repo = repositories?.find((r) => r.id === repoId)
  const { data: tree, isLoading: isTreeLoading } = useWikiTree(repoId ?? '')

  const [collapsedPages, setCollapsedPages] = useState<Set<string>>(new Set())
  const [sectionCollapsed, setSectionCollapsed] = useState(false)

  const togglePageCollapsed = (pageId: string) => {
    setCollapsedPages((prev) => {
      const next = new Set(prev)
      if (next.has(pageId)) next.delete(pageId)
      else next.add(pageId)
      return next
    })
  }

  let selectedPageId: null | string = null
  if (tree && pageSlug) {
    for (const [id, page] of tree.allPagesMap.entries()) {
      if (page.pageSlug === pageSlug) {
        selectedPageId = id
        break
      }
    }
  }

  const { data: selectedPage } = useWikiPage(repoId ?? '', selectedPageId)

  useEffect(() => {
    if (tree && selectedPage) {
      const parents: string[] = []
      let current: undefined | WikiPageDto = selectedPage
      while (current?.parentPageId) {
        parents.push(current.parentPageId)
        current = tree.allPagesMap.get(current.parentPageId)
      }
      if (parents.length > 0) {
        setCollapsedPages((prev) => {
          let changed = false
          const next = new Set(prev)
          for (const id of parents) {
            if (next.has(id)) {
              next.delete(id)
              changed = true
            }
          }
          return changed ? next : prev
        })
      }
    }
  }, [tree, selectedPage])

  const handleSelectPage = (page: WikiPageDto) => {
    onClose?.()
    void navigate({
      params: { pageSlug: page.pageSlug, repoId: repoId! },
      to: '/wiki/$repoId/$pageSlug',
    })
  }

  const renderWikiNavItems = (pages: WikiPageDto[], level: number) => {
    return pages.map((page) => {
      const children = tree?.childrenMap.get(page.id) || []
      const hasChildren = children.length > 0
      const isSelected = selectedPageId === page.id
      const isCollapsed = collapsedPages.has(page.id)
      const paddingLeftClass = level === 1 ? 'pl-3' : level === 2 ? 'pl-7' : 'pl-11'

      return (
        <div className="space-y-0.5" key={page.id}>
          <div
            className={`group flex cursor-pointer items-center justify-between rounded-md py-1.5 pr-3 text-sm transition-colors ${paddingLeftClass} ${
              isSelected
                ? 'bg-accent text-accent-foreground font-medium'
                : 'hover:bg-accent/50 text-muted-foreground hover:text-foreground'
            }`}
            onClick={() => handleSelectPage(page)}
          >
            <div className="flex min-w-0 items-center gap-2">
              {hasChildren ? (
                <button
                  className="text-muted-foreground/60 hover:text-foreground hover:bg-accent-foreground/10 -ml-1 rounded p-0.5 transition-colors"
                  onClick={(e) => {
                    e.stopPropagation()
                    togglePageCollapsed(page.id)
                  }}
                >
                  <ChevronRight
                    className={`h-3.5 w-3.5 transition-transform duration-200 ${!isCollapsed ? 'rotate-90' : ''}`}
                  />
                </button>
              ) : (
                <div className="w-[18px] flex-shrink-0" />
              )}
              <FileText
                className={`h-4 w-4 flex-shrink-0 ${isSelected ? 'text-foreground' : 'text-muted-foreground/60'}`}
              />
              <span className="truncate">{page.title}</span>
            </div>
          </div>

          {hasChildren && !isCollapsed && (
            <div className="space-y-0.5">{renderWikiNavItems(children, level + 1)}</div>
          )}
        </div>
      )
    })
  }

  if (!repoId) return null

  return (
    <div data-testid="wiki-sidebar-section">
      <div className="flex items-center gap-2 px-2 py-1.5">
        <Button
          aria-label="Back to repositories"
          className="h-6 w-6 shrink-0"
          onClick={() => {
            void navigate({ to: '/repos' })
          }}
          size="icon"
          variant="ghost"
        >
          <ArrowLeft className="h-3.5 w-3.5" />
        </Button>
        <div className="flex min-w-0 flex-1 items-center gap-1.5 overflow-hidden">
          <BookOpen className="text-muted-foreground h-3.5 w-3.5 shrink-0" />
          <span className="truncate text-xs font-semibold" title={repo?.name ?? repoId}>
            {repo?.name ?? repoId}
          </span>
        </div>
        <button
          aria-label={sectionCollapsed ? 'Expand wiki section' : 'Collapse wiki section'}
          className="text-muted-foreground hover:text-foreground -mr-1 rounded p-0.5"
          onClick={() => setSectionCollapsed((prev) => !prev)}
          type="button"
        >
          <ChevronRight
            className={cn('h-3.5 w-3.5 transition-transform', !sectionCollapsed && 'rotate-90')}
          />
        </button>
      </div>

      {!sectionCollapsed && (
        <div className="px-2 pb-2" data-testid="wiki-sidebar-section-content">
          {isTreeLoading ? (
            <div className="flex items-center justify-center py-6">
              <Loader2 className="text-muted-foreground h-4 w-4 animate-spin" />
            </div>
          ) : !tree || tree.topLevel.length === 0 ? (
            <p className="text-muted-foreground px-2 py-4 text-center text-xs">No pages found</p>
          ) : (
            <div className="space-y-0.5">{renderWikiNavItems(tree.topLevel, 1)}</div>
          )}
        </div>
      )}
    </div>
  )
}

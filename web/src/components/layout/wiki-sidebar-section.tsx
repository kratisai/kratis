import { useNavigate, useParams, useRouterState } from '@tanstack/react-router'
import { ArrowLeft, BookOpen, ChevronRight, FileText, GitBranch, Loader2 } from 'lucide-react'
import { useEffect, useState } from 'react'

import type { WikiPageDto } from '@/lib/wiki-api'

import { Button } from '@/components/ui/button'
import { useRepositories } from '@/hooks/use-repositories'
import {
  useTeamWikiPage,
  useTeamWikiStatus,
  useTeamWikiTree,
  useWikiPage,
  useWikiTree,
} from '@/hooks/use-wiki'
import { cn } from '@/lib/utils'
import { buildRepoWikiSplat, parseRepoWikiSplat } from '@/lib/wiki-route'

interface WikiSidebarSectionProps {
  onClose?: () => void
}

export function WikiSidebarSection({ onClose }: WikiSidebarSectionProps) {
  // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-assertion -- strict:false params may omit keys at runtime
  const params = useParams({ strict: false }) as {
    _splat?: string
    pageSlug?: string
    repoId?: string
  }
  const { _splat: splat, pageSlug: paramPageSlug, repoId: repoIdParam } = params
  const navigate = useNavigate()
  const pathname = useRouterState({ select: (state) => state.location.pathname })

  const { data: repositories } = useRepositories()
  const ingestedRepos = (repositories ?? []).filter((r) => r.ingestionStatus === 'SUCCESS')

  const isRepoWikiRoute = Boolean(repoIdParam || splat)
  const splatParsed = parseRepoWikiSplat(splat, repositories)
  const activeRepo = repoIdParam
    ? repositories?.find((r) => r.id === repoIdParam)
    : splatParsed.repo
  const pageSlug = splat ? splatParsed.pageSlug : paramPageSlug
  const activeRepoId = activeRepo?.id ?? repoIdParam ?? ''

  const { data: teamStatus } = useTeamWikiStatus()
  const hasTeamWiki = Boolean(teamStatus?.activeBatch) && ingestedRepos.length >= 2

  const { data: repoTree, isLoading: isRepoTreeLoading } = useWikiTree(
    isRepoWikiRoute ? activeRepoId : '',
  )
  const { data: teamTree, isLoading: isTeamTreeLoading } = useTeamWikiTree(
    !isRepoWikiRoute && hasTeamWiki,
  )

  const tree = isRepoWikiRoute ? repoTree : teamTree
  const isTreeLoading = isRepoWikiRoute ? isRepoTreeLoading : isTeamTreeLoading

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
  } else if (!isRepoWikiRoute && tree && !pageSlug && tree.topLevel.length > 0) {
    selectedPageId = tree.topLevel[0].id
  }

  const { data: selectedRepoPage } = useWikiPage(
    isRepoWikiRoute ? activeRepoId : '',
    isRepoWikiRoute ? selectedPageId : null,
  )
  const { data: selectedTeamPage } = useTeamWikiPage(!isRepoWikiRoute ? selectedPageId : null)
  const selectedPage = isRepoWikiRoute ? selectedRepoPage : selectedTeamPage

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
    if (!isRepoWikiRoute) {
      void navigate({
        params: { pageSlug: page.pageSlug },
        to: '/wiki/team/$pageSlug',
      })
      return
    }
    if (splat && activeRepo) {
      void navigate({
        params: { _splat: buildRepoWikiSplat(activeRepo.name, page.pageSlug) },
        to: '/wiki/repo/$',
      })
    } else {
      void navigate({
        params: { pageSlug: page.pageSlug, repoId: repoIdParam! },
        to: '/wiki/$repoId/$pageSlug',
      })
    }
  }

  const renderWikiNavItems = (pages: WikiPageDto[], level: number) => {
    return pages.map((page) => {
      const children = tree?.childrenMap.get(page.id) || []
      const hasChildren = children.length > 0
      const isSelected = selectedPageId === page.id
      const isCollapsed = collapsedPages.has(page.id)
      const paddingLeftClass = level === 1 ? 'pl-3' : level === 2 ? 'pl-6' : 'pl-9'

      return (
        <div className="space-y-0.5" key={page.id}>
          <div
            className={`group flex cursor-pointer items-center justify-between rounded-md py-1.5 pr-2 text-xs transition-colors ${paddingLeftClass} ${
              isSelected
                ? 'bg-accent text-accent-foreground font-medium'
                : 'hover:bg-accent/50 text-muted-foreground hover:text-foreground'
            }`}
            onClick={() => handleSelectPage(page)}
          >
            <div className="flex min-w-0 items-center gap-1.5">
              {hasChildren ? (
                <button
                  className="text-muted-foreground/60 hover:text-foreground hover:bg-accent-foreground/10 -ml-1 rounded p-0.5 transition-colors"
                  onClick={(e) => {
                    e.stopPropagation()
                    togglePageCollapsed(page.id)
                  }}
                >
                  <ChevronRight
                    className={`h-3 w-3 transition-transform duration-200 ${!isCollapsed ? 'rotate-90' : ''}`}
                  />
                </button>
              ) : (
                <div className="w-[14px] flex-shrink-0" />
              )}
              <FileText
                className={`h-3.5 w-3.5 flex-shrink-0 ${isSelected ? 'text-foreground' : 'text-muted-foreground/60'}`}
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

  if (!pathname.startsWith('/wiki')) return null

  return (
    <div className="mt-1 ml-2 border-l pl-2" data-testid="wiki-sidebar-section">
      <div className="flex items-center gap-1.5 px-1.5 py-1">
        {isRepoWikiRoute && ingestedRepos.length >= 2 && (
          <Button
            aria-label="Back to Team Wiki"
            className="h-5 w-5 shrink-0"
            onClick={() => {
              onClose?.()
              void navigate({ to: '/wiki' })
            }}
            size="icon"
            title="Team Wiki"
            variant="ghost"
          >
            <ArrowLeft className="h-3 w-3" />
          </Button>
        )}
        <div className="flex min-w-0 flex-1 items-center gap-1.5 overflow-hidden">
          <BookOpen className="text-muted-foreground h-3 w-3 shrink-0" />
          <span
            className="truncate text-[11px] font-semibold"
            title={isRepoWikiRoute ? (activeRepo?.name ?? activeRepoId) : 'Team Wiki'}
          >
            {isRepoWikiRoute ? (activeRepo?.name ?? activeRepoId) : 'Team Wiki'}
          </span>
        </div>
        <button
          aria-label={sectionCollapsed ? 'Expand wiki section' : 'Collapse wiki section'}
          className="text-muted-foreground hover:text-foreground rounded p-0.5"
          onClick={() => setSectionCollapsed((prev) => !prev)}
          type="button"
        >
          <ChevronRight
            className={cn('h-3 w-3 transition-transform', !sectionCollapsed && 'rotate-90')}
          />
        </button>
      </div>

      {!sectionCollapsed && (
        <div className="pb-1.5" data-testid="wiki-sidebar-section-content">
          {isTreeLoading ? (
            <div className="flex items-center justify-center py-4">
              <Loader2 className="text-muted-foreground h-3.5 w-3.5 animate-spin" />
            </div>
          ) : tree && tree.topLevel.length > 0 ? (
            <div className="space-y-0.5">{renderWikiNavItems(tree.topLevel, 1)}</div>
          ) : (
            <p className="text-muted-foreground px-2 py-2 text-xs">No pages found</p>
          )}

          {!isRepoWikiRoute && ingestedRepos.length > 0 && (
            <div className="mt-2 space-y-0.5 border-t pt-2">
              <div className="text-muted-foreground px-2 pb-1 text-[10px] font-semibold tracking-wider uppercase">
                Repositories
              </div>
              {ingestedRepos.map((repo) => (
                <button
                  className="text-muted-foreground hover:bg-accent/50 hover:text-foreground flex w-full items-center gap-2 rounded-md px-2 py-1 text-left text-xs transition-colors"
                  key={repo.id}
                  onClick={() => {
                    onClose?.()
                    void navigate({
                      params: { _splat: repo.name },
                      to: '/wiki/repo/$',
                    })
                  }}
                  type="button"
                >
                  <GitBranch className="h-3 w-3 shrink-0 opacity-60" />
                  <span className="truncate">{repo.name}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

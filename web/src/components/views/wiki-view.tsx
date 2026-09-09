'use client'

import { useNavigate, useParams } from '@tanstack/react-router'
import { ArrowLeft, BookOpen, ChevronRight, Loader2 } from 'lucide-react'
import React, { useEffect } from 'react'

import type { WikiPageDto } from '@/lib/wiki-api'

import { MarkdownMessage } from '@/components/chat/markdown-message'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { ScrollArea } from '@/components/ui/scroll-area'
import { useRepositories } from '@/hooks/use-repositories'
import { useWikiPage, useWikiTree } from '@/hooks/use-wiki'

export function WikiView() {
  // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-assertion
  const params = useParams({ strict: false }) as { pageSlug?: string; repoId?: string }
  const { pageSlug: pageSlugParam, repoId } = params
  const pageSlug = pageSlugParam
  const navigate = useNavigate()

  const { data: repositories } = useRepositories()
  const repo = repositories?.find((r) => r.id === repoId)

  const { data: tree, isLoading: isTreeLoading } = useWikiTree(repoId ?? '')

  let selectedPageId: null | string = null
  if (tree && pageSlug) {
    for (const [id, page] of tree.allPagesMap.entries()) {
      if (page.pageSlug === pageSlug) {
        selectedPageId = id
        break
      }
    }
  }

  const { data: selectedPage, isLoading: isPageLoading } = useWikiPage(repoId ?? '', selectedPageId)

  useEffect(() => {
    if (tree && !pageSlug && tree.topLevel.length > 0) {
      void navigate({
        params: { pageSlug: tree.topLevel[0].pageSlug, repoId: repoId! },
        replace: true,
        to: '/wiki/$repoId/$pageSlug',
      })
    }
  }, [tree, pageSlug, repoId, navigate])

  const handleSelectPage = (page: WikiPageDto) => {
    void navigate({
      params: { pageSlug: page.pageSlug, repoId: repoId! },
      to: '/wiki/$repoId/$pageSlug',
    })
  }

  const breadcrumbs: WikiPageDto[] = []
  if (tree && selectedPage) {
    let current: undefined | WikiPageDto = selectedPage
    while (current) {
      breadcrumbs.unshift(current)
      current = current.parentPageId ? tree.allPagesMap.get(current.parentPageId) : undefined
    }
  }

  if (!repo) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-center">
          <h2 className="text-xl font-semibold">Repository not found</h2>
          <Button
            className="mt-4"
            onClick={() => {
              void navigate({ to: '/repos' })
            }}
          >
            <ArrowLeft className="mr-2 h-4 w-4" />
            Back to Repositories
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="flex min-h-full flex-1 flex-col overflow-visible md:h-full md:overflow-hidden">
      {pageSlug == null ? (
        <div className="flex h-full items-center justify-center">
          <div className="text-muted-foreground text-center">
            <BookOpen className="mx-auto mb-3 h-10 w-10 opacity-40" />
            <p className="text-sm">Select a page to read</p>
          </div>
        </div>
      ) : isPageLoading || isTreeLoading ? (
        <div className="flex h-full items-center justify-center">
          <Loader2 className="text-muted-foreground h-6 w-6 animate-spin" />
        </div>
      ) : selectedPage ? (
        <ScrollArea className="min-h-full overflow-visible md:h-full md:overflow-hidden [&>[data-radix-scroll-area-viewport]]:!overflow-visible md:[&>[data-radix-scroll-area-viewport]]:!overflow-auto">
          <div className="mx-auto max-w-5xl p-4 sm:p-8" data-testid="wiki-content">
            {breadcrumbs.length > 0 && (
              <div className="text-muted-foreground mb-4 flex flex-wrap items-center gap-1 text-xs">
                <span className="font-medium">Wiki</span>
                {breadcrumbs.map((crumb, idx) => (
                  <React.Fragment key={crumb.id}>
                    <ChevronRight className="h-3 w-3 opacity-60" />
                    {idx === breadcrumbs.length - 1 ? (
                      <span className="text-foreground max-w-[150px] truncate font-medium">
                        {crumb.title}
                      </span>
                    ) : (
                      <button
                        className="hover:text-foreground max-w-[150px] truncate transition-colors hover:underline"
                        onClick={() => handleSelectPage(crumb)}
                      >
                        {crumb.title}
                      </button>
                    )}
                  </React.Fragment>
                ))}
              </div>
            )}

            <div className="mb-6">
              <h1 className="text-2xl font-semibold">{selectedPage.title}</h1>
              <p className="text-muted-foreground mt-1 text-xs">/{selectedPage.pageSlug}</p>
            </div>

            <Card>
              <CardContent className="p-6">
                <MarkdownMessage content={selectedPage.content} />
              </CardContent>
            </Card>
          </div>
        </ScrollArea>
      ) : (
        <div className="flex h-full items-center justify-center">
          <div className="text-muted-foreground text-center">
            <BookOpen className="mx-auto mb-3 h-10 w-10 opacity-40" />
            <p className="text-sm">Page not found</p>
          </div>
        </div>
      )}
    </div>
  )
}

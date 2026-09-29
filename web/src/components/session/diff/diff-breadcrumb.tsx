import { Bookmark } from 'lucide-react'

import { useDiffReviewStore } from '@/store/diff-review-store'

export function DiffBreadcrumb() {
  const activeBreadcrumb = useDiffReviewStore((state) => state.activeStickyBreadcrumb)

  if (!activeBreadcrumb) return null

  return (
    <div
      className="bg-background/95 sticky top-0 z-20 flex items-center gap-2 border-b px-4 py-1.5 text-xs font-medium shadow-xs backdrop-blur-md transition-all"
      data-testid="sticky-diff-breadcrumb"
    >
      <Bookmark className="text-primary h-3.5 w-3.5 shrink-0" />
      <span className="text-foreground truncate font-semibold">{activeBreadcrumb.path}</span>
      {activeBreadcrumb.header && (
        <>
          <span className="text-muted-foreground">›</span>
          <span className="text-muted-foreground truncate font-mono">
            {activeBreadcrumb.header}
          </span>
        </>
      )}
    </div>
  )
}

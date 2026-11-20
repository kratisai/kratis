import type { WikiPageDto } from '@/lib/wiki-api'

export interface WikiTree {
  allPagesMap: Map<string, WikiPageDto>
  childrenMap: Map<string, WikiPageDto[]>
  topLevel: WikiPageDto[]
}

export function buildWikiTreeFromList(pages: WikiPageDto[]): WikiTree {
  const allPagesMap = new Map<string, WikiPageDto>()
  const childrenMap = new Map<string, WikiPageDto[]>()
  const topLevel: WikiPageDto[] = []

  for (const page of pages) {
    if (allPagesMap.has(page.id)) continue
    allPagesMap.set(page.id, page)
    if (page.parentPageId == null || page.parentPageId === page.id) {
      topLevel.push(page)
    } else {
      const siblings = childrenMap.get(page.parentPageId) ?? []
      siblings.push(page)
      childrenMap.set(page.parentPageId, siblings)
    }
  }

  topLevel.sort(compareWikiPages)
  for (const siblings of childrenMap.values()) {
    siblings.sort(compareWikiPages)
  }

  return { allPagesMap, childrenMap, topLevel }
}

export function compareWikiPages(a: WikiPageDto, b: WikiPageDto): number {
  return (
    a.orderIndex - b.orderIndex ||
    a.title.localeCompare(b.title) ||
    a.pageSlug.localeCompare(b.pageSlug)
  )
}
